package com.originex.starter.security;

import com.originex.starter.OriginexProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * OAuth2 resource-server foundation for Originex services (see
 * {@code dev/AUTH_DESIGN.md} §3). Wires JWT-based authentication when
 * {@code originex.security.enabled=true}; contributes nothing that changes
 * behaviour while disabled (the default).
 *
 * <p><b>Default-off / no behaviour change.</b> The
 * {@code spring-boot-starter-oauth2-resource-server} dependency is declared
 * {@code optional} in the starter, so Spring Security is not on a service's
 * classpath unless the service opts in. Until then, {@link ConditionalOnClass}
 * makes this whole configuration back off and Spring Boot's own security
 * auto-configuration is likewise absent — behaviour is identical to today's
 * header-based tenant resolution.
 *
 * <p>Once a service opts into the dependency:
 * <ul>
 *   <li>{@code enabled=true} → a stateless resource-server {@link SecurityFilterChain}
 *       authenticates every request (bar {@code /actuator/health}) against a
 *       JWT validated by {@link #jwtDecoder}. Authorization rules
 *       ({@code @PreAuthorize}, scopes) and the claim→authority converter are
 *       added in later commits; this commit establishes authentication only.</li>
 *   <li>{@code enabled=false} (default) → a permit-all chain that suppresses Spring
 *       Boot's default lock-down, preserving the pre-auth behaviour. No service
 *       reaches this branch until it has opted into the dependency.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class})
@EnableConfigurationProperties(OriginexProperties.class)
public class SecurityAutoConfiguration {

    private static final String ENABLED = "enabled";
    private static final String PREFIX = "originex.security";

    /**
     * Resource-server chain: authenticate every request (except health) using the
     * bearer JWT. Active only when RLS-style security is explicitly enabled.
     */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED, havingValue = "true")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain originexResourceServerFilterChain(HttpSecurity http,
                                                                 JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Only liveness/readiness is public; other actuator endpoints
                        // require authentication (dev/AUTH_DESIGN.md §11).
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    /**
     * Disabled chain: when the dependency is present but security is off, permit
     * everything so Spring Boot's default (basic-auth lock-down) does not engage —
     * preserving today's open behaviour. Inert until a service opts in.
     */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED, havingValue = "false", matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain originexSecurityDisabledFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * JWT decoder validating signature (RS256 via the IdP JWKS), timestamps, issuer,
     * and — when configured — the per-service audience. Fail-loud: enabling security
     * without an {@code issuer-uri} or {@code jwk-set-uri} stops the service at boot
     * rather than starting with no way to validate tokens.
     */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ENABLED, havingValue = "true")
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(OriginexProperties properties) {
        OriginexProperties.SecurityProperties s = properties.getSecurity();

        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(s.getJwkSetUri())) {
            decoder = NimbusJwtDecoder.withJwkSetUri(s.getJwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256) // allowlist: reject 'none'/HS*
                    .build();
        } else if (StringUtils.hasText(s.getIssuerUri())) {
            decoder = NimbusJwtDecoder.withIssuerLocation(s.getIssuerUri()).build();
        } else {
            throw new IllegalStateException(
                    "originex.security.enabled=true but neither originex.security.issuer-uri "
                            + "nor originex.security.jwk-set-uri is configured");
        }

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        if (StringUtils.hasText(s.getIssuerUri())) {
            validators.add(new JwtIssuerValidator(s.getIssuerUri()));
        }
        if (StringUtils.hasText(s.getAudience())) {
            validators.add(audienceValidator(s.getAudience()));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** Requires the token's {@code aud} to include this service's configured audience. */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "Required audience '" + audience + "' is missing", null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
