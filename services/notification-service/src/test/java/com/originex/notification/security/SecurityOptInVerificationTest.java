package com.originex.notification.security;

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
 * Verifies notification-service has <b>opted in</b> to the OAuth2 resource server, and that the opt-in
 * causes <b>no behaviour change while security is disabled</b>.
 *
 * <p>Unlike the other canaries, notification's opt-in is <i>ceremonial</i>: it has no use-case port and
 * no {@code @PreAuthorize}, so the dependency is here purely for mechanism uniformity (a named machine
 * principal on the consumer, and the ability to run the RLS IT under {@code security.enabled=true}). The
 * risk this test guards is the same regardless of intent: notification serves actuator over HTTP
 * ({@code spring-boot-starter-web}), so pulling Spring Security onto the classpath must not trigger Boot's
 * default lock-down (HTTP Basic + generated password). The starter's permit-all chain backs it off while
 * {@code originex.security.enabled=false} (the default) — asserted below.
 */
@DisplayName("Security opt-in verification (notification-service)")
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
                        "originex.security.audience=svc-notification")
                .run(ctx -> {
                    assertThat(ctx).hasBean(RESOURCE_SERVER_CHAIN);
                    assertThat(ctx).doesNotHaveBean(DISABLED_CHAIN);
                    assertThat(ctx).hasSingleBean(JwtDecoder.class);
                });
    }
}
