package com.originex.starter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OriginexJwtAuthenticationConverter} maps OAuth2 scopes to {@code SCOPE_*}
 * authorities and uses the token {@code sub} as the principal name. Role→authority
 * mapping and authorization are deliberately out of scope for this commit.
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
}
