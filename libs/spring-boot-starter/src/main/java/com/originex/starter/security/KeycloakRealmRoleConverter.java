package com.originex.starter.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to {@code ROLE_<name>}
 * Spring authorities (see {@code dev/AUTH_DESIGN.md} §4.5). Resource-scoped roles
 * ({@code resource_access}) are intentionally not mapped here — the platform's RBAC
 * uses realm roles + OAuth2 scopes.
 *
 * <p>Robust to a missing or malformed claim: an absent {@code realm_access}, a
 * non-object value, or a non-list {@code roles} yields no authorities rather than
 * an error.
 */
public final class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS_CLAIM = "realm_access";
    static final String ROLES = "roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return List.of();
        }
        Object rolesValue = realmAccess.get(ROLES);
        if (!(rolesValue instanceof Collection<?> roles)) {
            return List.of();
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Object role : roles) {
            if (role == null) {
                continue;
            }
            String name = role.toString().trim();
            if (!name.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority(OriginexRoles.AUTHORITY_PREFIX + name));
            }
        }
        return authorities;
    }
}
