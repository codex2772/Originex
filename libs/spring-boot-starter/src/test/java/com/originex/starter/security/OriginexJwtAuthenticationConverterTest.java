package com.originex.starter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OriginexJwtAuthenticationConverter} maps OAuth2 scopes to {@code SCOPE_*}
 * authorities and derives the principal name from the token — the {@code sub} for
 * human principals, or the client id for service accounts. Role→authority mapping
 * and authorization are deliberately out of scope for this commit.
 */
@DisplayName("OriginexJwtAuthenticationConverter")
class OriginexJwtAuthenticationConverterTest {

    private final OriginexJwtAuthenticationConverter converter = new OriginexJwtAuthenticationConverter();

    @Test
    @DisplayName("maps scopes to SCOPE_ authorities and sets the subject as principal")
    void mapsScopesAndSubject() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("scope", "loans:read loans:disburse")
                .build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        assertThat(auth.getName()).isEqualTo("user-1");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_loans:read", "SCOPE_loans:disburse");
    }

    @Test
    @DisplayName("service-account token (no sub): principal name is the client id")
    void serviceAccountUsesClientIdAsPrincipal() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("azp", "svc-los")
                .claim("scope", "customers:read")
                .build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        assertThat(auth.getName()).isEqualTo("svc-los");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_customers:read");
    }
}
