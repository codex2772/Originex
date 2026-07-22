package com.originex.lms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies lms-service has <b>opted in</b> to the OAuth2 resource server, and that the opt-in causes
 * <b>no behaviour change while security is disabled</b>.
 *
 * <p>lms is the first canary to add the opt-in <i>as part of the authz pass itself</i> — its Phase-2 RLS
 * canary is consumer/scheduler-driven ({@code LmsRlsConsumerAndSchedulerIntegrationTest}) and never
 * needed a JWT path, so the dependency was absent. Adding {@code @PreAuthorize} to the port needs Spring
 * Security's method-security on the classpath; this test guards that adding it does not trigger Boot's
 * default lock-down (HTTP Basic + generated password) — the starter's permit-all chain backs it off.
 */
@DisplayName("Security opt-in verification (lms-service)")
class SecurityOptInVerificationTest {

    private static final String DISABLED_CHAIN = "originexSecurityDisabledFilterChain";
    private static final String RESOURCE_SERVER_CHAIN = "originexResourceServerFilterChain";
    private static final String BOOT_DEFAULT_CHAIN = "defaultSecurityFilterChain";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class,
                    OAuth2ResourceServerAutoConfiguration.class,
                    com.originex.starter.security.SecurityAutoConfiguration.class));

    @Test
    @DisplayName("the opt-in dependency is on the classpath")
    void oauth2DependencyPresent() {
        assertThatCode(() -> Class.forName(
                "org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter"))
                .doesNotThrowAnyException();
        assertThat(JwtDecoder.class).isNotNull();
    }

    @Test
    @DisplayName("security disabled (default): permit-all chain engages, no Boot lock-down, no decoder")
    void disabledDoesNotLockDown() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean(DISABLED_CHAIN);
            assertThat(ctx).doesNotHaveBean(BOOT_DEFAULT_CHAIN);
            assertThat(ctx).doesNotHaveBean(RESOURCE_SERVER_CHAIN);
            assertThat(ctx).doesNotHaveBean(JwtDecoder.class);
        });
    }

    @Test
    @DisplayName("security enabled: resource-server chain engages, permit-all chain gone, decoder present")
    void enabledActivatesResourceServer() {
        runner.withPropertyValues(
                        "originex.security.enabled=true",
                        "originex.security.jwk-set-uri=https://idp.example.com/realms/originex/protocol/openid-connect/certs",
                        "originex.security.audience=svc-lms")
                .run(ctx -> {
                    assertThat(ctx).hasBean(RESOURCE_SERVER_CHAIN);
                    assertThat(ctx).doesNotHaveBean(DISABLED_CHAIN);
                    assertThat(ctx).hasSingleBean(JwtDecoder.class);
                });
    }
}
