package com.originex.starter.security;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

/**
 * Establishes the two contexts a <b>machine actor</b> — a Kafka consumer or a scheduled job, neither
 * of which carries a JWT — needs to run a tenant-scoped, authorized unit of work:
 * <ul>
 *   <li>the <b>tenant identity</b> ({@link TenantContextHolder}) that RLS routes on, and</li>
 *   <li>a minimal <b>authorization</b> {@link SecurityContextHolder SecurityContext} carrying only
 *       the scope(s) the wrapped path actually invokes — never a blanket "system can do anything"
 *       authority. A minimally-scoped principal is bounded; a universal bypass is a
 *       privilege-escalation surface only as safe as the guarantee nothing external can trigger it.</li>
 * </ul>
 *
 * <p><b>Orthogonal axes, one lifecycle.</b> Identity and authority are different concerns, but they
 * share a single boundary: {@link #establish} sets both, {@link #clear} clears both, and callers
 * MUST pair them in one {@code try/finally} so neither context can exist — or leak past the work
 * boundary into a later invocation — without the other. (The RLS integration tests already prove
 * tenant-context leakage across consumer calls is a real hazard.)
 *
 * <p><b>This is not {@code runAsSystem}.</b> The RLS system flag routes to BYPASSRLS (cross-tenant);
 * a consumer processing one tenant's event stays tenant-scoped on the app role. Authorization is a
 * separate axis layered at the same boundary — deliberately not folded into the RLS system flag,
 * which would flip the consumer onto BYPASSRLS and break tenant isolation.
 */
public final class MachineActorContext {

    /** Principal name machine-actor authentications run under (for audit/logging). */
    public static final String MACHINE_PRINCIPAL = "system:machine";

    private MachineActorContext() {
    }

    /**
     * Bind the tenant and a minimal authorization context for the current (virtual) thread — grants
     * exactly {@code scopes}, nothing more. Must be paired with {@link #clear()} in a {@code finally}.
     */
    public static void establish(TenantContext tenant, String... scopes) {
        TenantContextHolder.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(machineAuthentication(scopes));
    }

    /** Clear both contexts. Safe to call in a {@code finally} even if {@link #establish} threw midway. */
    public static void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    /** A pre-authenticated machine principal bearing only {@code scopes} as {@code SCOPE_*} authorities. */
    public static Authentication machineAuthentication(String... scopes) {
        List<GrantedAuthority> authorities = Arrays.stream(scopes)
                .map(OriginexScopes::authority)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();
        return new MachineAuthenticationToken(authorities);
    }

    /** Pre-authenticated token for machine actors — no credentials, fixed minimal authorities. */
    static final class MachineAuthenticationToken extends AbstractAuthenticationToken {
        MachineAuthenticationToken(List<GrantedAuthority> authorities) {
            super(authorities);
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return MACHINE_PRINCIPAL;
        }
    }
}
