package com.originex.starter.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Builds the {@link org.springframework.security.core.Authentication} from a
 * verified JWT (see {@code dev/AUTH_DESIGN.md} §4.5).
 *
 * <p>Builds the granted authorities from both authorization dimensions
 * (see {@code dev/AUTH_DESIGN.md} §4.5): OAuth2 <b>scopes</b> ({@code scope}/{@code scp})
 * → {@code SCOPE_*}, and Keycloak realm <b>roles</b> ({@code realm_access.roles})
 * → {@code ROLE_*} (via {@link KeycloakRealmRoleConverter}). The principal name is
 * resolved by {@link JwtPrincipalResolver} (the token {@code sub} for humans, or the
 * client id for service accounts). This only populates the {@code Authentication};
 * authorization is enforced by {@code @PreAuthorize} at the use-case ports.
 */
public final class OriginexJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();
    private final KeycloakRealmRoleConverter realmRoleAuthorities = new KeycloakRealmRoleConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        authorities.addAll(scopeAuthorities.convert(jwt));
        authorities.addAll(realmRoleAuthorities.convert(jwt));

        String principalName = JwtPrincipalResolver.principalName(jwt);
        return principalName != null
                ? new JwtAuthenticationToken(jwt, authorities, principalName)
                : new JwtAuthenticationToken(jwt, authorities);
    }
}
