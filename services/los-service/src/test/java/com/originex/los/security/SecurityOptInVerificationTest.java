package com.originex.los.security;

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
 * Verifies that los-service has <b>opted in</b> to the OAuth2 resource server, and
 * that the opt-in causes <b>no behaviour change while security is disabled</b>.
 *
 * <p>This is Phase-1 security work, executed here because los's Phase-2 RLS canary
 * depends on it: {@code SecurityAutoConfiguration} only builds a {@code JwtDecoder}
 * when {@code originex.security.enabled=true}, and without this dependency on the
 * classpath there is no decoder — so a JWT-driven RLS test is impossible. It is
 * kept as its own commit rather than folded into the RLS work (see the
 * three-commit shape in {@code dev/RLS_ENABLEMENT.md}).
 *
 * <p>The key guarantee: adding {@code spring-boot-starter-oauth2-resource-server}
 * must NOT trigger Spring Boot's default security lock-down (HTTP Basic + a
 * generated password on every endpoint). It doesn't, because the platform starter
 * contributes a permit-all {@code SecurityFilterChain} when
 * {@code originex.security.enabled=false} (the default), which makes Boot's
 * {@code @ConditionalOnDefaultWebSecurity} chain back off.
 *
 * <p>These are lightweight context-slice / classpath checks — no full service boot,
 * no database, no IdP. In particular the runner declares its own autoconfigurations
 * and never reads {@code application.yml}, so it is unaffected by the {@code rls}
 * profile los picks up in the canary's final commit.
 */
@DisplayName("Security opt-in verification (los-service)")
class SecurityOptInVerificationTest {

    private static final String DISABLED_CHAIN = "originexSecurityDisabledFilterChain";
    private static final String RESOURCE_SERVER_CHAIN = "originexResourceServerFilterChain";
    private static final String BOOT_DEFAULT_CHAIN = "defaultSecurityFilterChain";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    // Spring MVC pieces so the enabled resource-server chain's
                    // requestMatchers("/actuator/health/**") can resolve the shared
                    // mvcHandlerMappingIntrospector bean (present in a real service).
                    WebMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class,
                    OAuth2ResourceServerAutoConfiguration.class,
                    com.originex.starter.security.SecurityAutoConfiguration.class));

    @Test
    @DisplayName("the opt-in dependency is on the classpath")
    void oauth2DependencyPresent() {
        // Compiles + loads only because los-service declares the dependency.
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
            assertThat(ctx).doesNotHaveBean(BOOT_DEFAULT_CHAIN); // Boot default backed off
            assertThat(ctx).doesNotHaveBean(RESOURCE_SERVER_CHAIN);
            assertThat(ctx).doesNotHaveBean(JwtDecoder.class);
        });
    }

    @Test
    @DisplayName("security disabled explicitly: same permit-all posture")
    void disabledExplicitlySamePosture() {
        runner.withPropertyValues("originex.security.enabled=false").run(ctx -> {
            assertThat(ctx).hasBean(DISABLED_CHAIN);
            assertThat(ctx).doesNotHaveBean(BOOT_DEFAULT_CHAIN);
        });
    }

    @Test
    @DisplayName("security enabled: resource-server chain engages, permit-all chain gone, decoder present")
    void enabledActivatesResourceServer() {
        runner.withPropertyValues(
                        "originex.security.enabled=true",
                        "originex.security.jwk-set-uri=https://idp.example.com/realms/originex/protocol/openid-connect/certs",
                        "originex.security.audience=svc-los")
                .run(ctx -> {
                    assertThat(ctx).hasBean(RESOURCE_SERVER_CHAIN);
                    assertThat(ctx).doesNotHaveBean(DISABLED_CHAIN);
                    assertThat(ctx).hasSingleBean(JwtDecoder.class);
                });
    }
}
