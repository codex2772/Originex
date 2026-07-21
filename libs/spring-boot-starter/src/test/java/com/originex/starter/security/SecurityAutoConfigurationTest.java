package com.originex.starter.security;

import com.originex.starter.OriginexProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-1 gating + fail-loud behaviour for the OAuth2 resource-server foundation.
 * No IdP is contacted — {@code NimbusJwtDecoder} fetches JWKS lazily, so the
 * decoder bean builds offline. The web {@code SecurityFilterChain} beans are
 * {@code @ConditionalOnWebApplication(SERVLET)} and are exercised by a running
 * service in a later commit; here we assert the decoder gating (the security beans
 * are absent unless explicitly enabled) and the default-off invariant.
 */
class SecurityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class));

    @Test
    void securityBeansAbsentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class));
    }

    @Test
    void securityBeansAbsentWhenDisabled() {
        runner.withPropertyValues("originex.security.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class));
    }

    @Test
    void jwtDecoderPresentWhenEnabledWithJwkSetUri() {
        runner.withPropertyValues(
                        "originex.security.enabled=true",
                        "originex.security.jwk-set-uri=https://idp.example.com/realms/originex/protocol/openid-connect/certs",
                        "originex.security.audience=svc-test")
                .run(ctx -> assertThat(ctx).hasSingleBean(JwtDecoder.class));
    }

    @Test
    void failsLoudlyWhenEnabledButNoIssuerOrJwks() {
        runner.withPropertyValues("originex.security.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("originex.security.issuer-uri"));
    }

    @Test
    void securityPropertiesDefaultToDisabled() {
        OriginexProperties.SecurityProperties props = new OriginexProperties.SecurityProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getIssuerUri()).isNull();
        assertThat(props.getJwkSetUri()).isNull();
        assertThat(props.getAudience()).isNull();
        // Fail-secure default posture, and a fail-closed (empty) fallback allowlist.
        assertThat(props.getMode()).isEqualTo(AuthMode.ENFORCED);
        assertThat(props.getPermissive().getTrustedFallbackCidrs()).isEmpty();
    }
}
