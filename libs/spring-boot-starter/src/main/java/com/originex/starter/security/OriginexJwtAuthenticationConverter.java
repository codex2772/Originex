package com.originex.starter.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;

/**
 * Builds the {@link org.springframework.security.core.Authentication} from a
 * verified JWT (see {@code dev/AUTH_DESIGN.md} §4.5).
 *
 * <p>This commit maps only OAuth2 <b>scopes</b> ({@code scope}/{@code scp}) to
 * {@code SCOPE_*} authorities — the standard resource-server behaviour — and sets
 * the principal name to the token {@code sub}. Mapping of realm <b>roles</b> to
 * {@code ROLE_*} authorities and any authorization rules are intentionally
 * deferred to the RBAC commit; nothing here enforces authorization.
 */
public final class OriginexJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = scopeAuthorities.convert(jwt);
        // Principal name = 'sub'; when absent, JwtAuthenticationToken derives it from
        // the token and the resolution filter rejects the request (no subject).
        String principalName = jwt.getSubject();
        return principalName != null
                ? new JwtAuthenticationToken(jwt, authorities, principalName)
                : new JwtAuthenticationToken(jwt, authorities);
    }
}
