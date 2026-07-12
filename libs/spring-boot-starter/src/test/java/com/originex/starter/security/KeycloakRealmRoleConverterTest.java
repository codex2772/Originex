package com.originex.starter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeycloakRealmRoleConverter} maps {@code realm_access.roles} to
 * {@code ROLE_*} authorities, and is robust to a missing/malformed claim.
 */
@DisplayName("KeycloakRealmRoleConverter")
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1");
    }

    @Test
    @DisplayName("realm roles become ROLE_ authorities")
    void mapsRealmRoles() {
        Jwt jwt = jwt().claim("realm_access", Map.of("roles", List.of("UNDERWRITER", "FINANCE"))).build();

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_UNDERWRITER", "ROLE_FINANCE");
    }

    @Test
    @DisplayName("absent realm_access → no authorities")
    void absentRealmAccess() {
        assertThat(converter.convert(jwt().build())).isEmpty();
    }

    @Test
    @DisplayName("realm_access without a roles list → no authorities")
    void malformedRolesClaim() {
        Jwt jwt = jwt().claim("realm_access", Map.of("notRoles", "x")).build();
        assertThat(converter.convert(jwt)).isEmpty();
    }
}
