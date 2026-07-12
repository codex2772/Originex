package com.originex.starter.security;

import com.originex.common.security.SubjectContext;
import com.originex.common.security.SubjectContextHolder;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Derives tenant and subject context from the <b>verified</b> JWT and installs it
 * for the duration of the request (see {@code dev/AUTH_DESIGN.md} §5).
 *
 * <p>Registered <b>inside</b> the Spring Security chain, immediately after the
 * bearer-token authentication filter, so it runs only once the token's signature
 * and standard claims are validated, and before the controller / {@code @Transactional}
 * boundary opens (so {@code SET LOCAL app.tenant_id} sees the claim-derived tenant).
 * It replaces trust in {@code X-Tenant-Id} for authenticated requests: the tenant
 * comes from the token, not a client header.
 *
 * <p>The principal (human user / customer / service account) is classified by
 * {@link JwtPrincipalResolver}. Business-claim validation (this is not
 * authorization — no roles/scopes are checked here):
 * <ul>
 *   <li>no usable identity (no {@code sub} and no {@code azp}/{@code client_id}) → 403;</li>
 *   <li>no {@code tenant_id} → 403 (cannot establish a tenant; platform/cross-tenant
 *       and tenantless-system-service principals are handled by a later commit);</li>
 *   <li>malformed {@code tenant_id} (not a UUID) → 400.</li>
 * </ul>
 *
 * <p>Both contexts (and their MDC keys) are always cleared in a {@code finally}
 * block, so a pooled virtual thread never leaks one request's identity into the
 * next — on the success and the exception path alike.
 */
public class TenantClaimResolutionFilter extends OncePerRequestFilter {

    /** Claim carrying the tenant id (UUID). Matches the Kafka {@code tenant_id} header. */
    static final String TENANT_CLAIM = "tenant_id";

    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_SUBJECT = "sub";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            // Unauthenticated (e.g. a permit-all endpoint such as /actuator/health):
            // nothing to resolve, and no context to set.
            filterChain.doFilter(request, response);
            return;
        }

        Jwt jwt = jwtAuth.getToken();

        Optional<SubjectContext> principal = JwtPrincipalResolver.resolve(jwt);
        if (principal.isEmpty()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "unresolvable-principal",
                    "Token carries no subject or client identity");
            return;
        }

        String tenantId = jwt.getClaimAsString(TENANT_CLAIM);
        if (tenantId == null || tenantId.isBlank()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "missing-tenant",
                    "Token is missing the required '" + TENANT_CLAIM + "' claim");
            return;
        }
        try {
            UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "invalid-tenant",
                    "Claim '" + TENANT_CLAIM + "' is not a valid UUID");
            return;
        }

        SubjectContext subject = principal.get();
        try {
            TenantContextHolder.set(TenantContext.of(tenantId, tenantId));
            SubjectContextHolder.set(subject);
            MDC.put(MDC_TENANT, tenantId);
            MDC.put(MDC_SUBJECT, subject.subject());

            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            SubjectContextHolder.clear();
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_SUBJECT);
        }
    }

    private static void reject(HttpServletResponse response, int status, String problem, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"type\":\"https://api.originex.io/problems/" + problem + "\","
                        + "\"title\":\"" + problem + "\","
                        + "\"status\":" + status + ","
                        + "\"detail\":\"" + detail + "\"}");
    }
}
