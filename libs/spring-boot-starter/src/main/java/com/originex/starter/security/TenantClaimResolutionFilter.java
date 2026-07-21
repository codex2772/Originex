package com.originex.starter.security;

import com.originex.common.security.SubjectContext;
import com.originex.common.security.SubjectContextHolder;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Derives tenant and subject context from the <b>verified</b> JWT and installs it
 * for the duration of the request (see {@code dev/AUTH_DESIGN.md} §5, §8).
 *
 * <p>Registered inside the Spring Security chain, immediately after the
 * bearer-token authentication filter, so it runs once the token is validated and
 * before the controller / {@code @Transactional} boundary opens.
 *
 * <p>Behaviour depends on {@link AuthMode}:
 * <ul>
 *   <li><b>ENFORCED</b> — authentication is required upstream; the principal
 *       (human/customer/service, via {@link JwtPrincipalResolver}) and tenant come
 *       only from claims; {@code X-Tenant-Id} is ignored (a mismatching header is
 *       logged/counted but the claim wins).</li>
 *   <li><b>PERMISSIVE</b> — same when a token is present; when <b>no</b> token is
 *       present it may fall back to the {@code X-Tenant-Id} header, but only from a
 *       trusted source network (empty allowlist ⇒ never). The migration observe
 *       state (§8.2).</li>
 * </ul>
 *
 * <p>Business-claim validation for an authenticated request: no usable identity → 403;
 * no {@code tenant_id} → 403; malformed {@code tenant_id} → 400. Both contexts (and
 * MDC keys) are always cleared in a {@code finally} block.
 */
public class TenantClaimResolutionFilter extends OncePerRequestFilter {

    /** Claim carrying the tenant id (UUID). Matches the Kafka {@code tenant_id} header. */
    static final String TENANT_CLAIM = "tenant_id";
    static final String FALLBACK_METRIC = "originex.security.permissive.header_fallback";
    static final String MISMATCH_METRIC = "originex.security.tenant_mismatch";

    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_SUBJECT = "sub";
    private static final Logger log = LoggerFactory.getLogger(TenantClaimResolutionFilter.class);

    private final AuthMode mode;
    private final String tenantHeaderName;
    private final List<IpAddressMatcher> trustedFallbackMatchers;
    private final MeterRegistry meterRegistry;

    /** Defaults for tests: ENFORCED, standard header, no fallback, throwaway registry. */
    public TenantClaimResolutionFilter() {
        this(AuthMode.ENFORCED, "X-Tenant-Id", List.of(), new SimpleMeterRegistry());
    }

    public TenantClaimResolutionFilter(AuthMode mode,
                                       String tenantHeaderName,
                                       List<String> trustedFallbackCidrs,
                                       MeterRegistry meterRegistry) {
        this.mode = mode;
        this.tenantHeaderName = tenantHeaderName;
        this.meterRegistry = meterRegistry;
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : trustedFallbackCidrs) {
            matchers.add(new IpAddressMatcher(cidr)); // fail-fast on invalid CIDR config
        }
        this.trustedFallbackMatchers = List.copyOf(matchers);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            resolveFromToken(jwtAuth.getToken(), request, response, filterChain);
            return;
        }

        // No authenticated token. In PERMISSIVE, optionally fall back to the header.
        if (mode == AuthMode.PERMISSIVE) {
            permissiveHeaderFallback(request, response, filterChain);
            return;
        }
        // ENFORCED: an unauthenticated request only reaches here for permit-all
        // endpoints (e.g. /actuator/health); proceed with no context.
        filterChain.doFilter(request, response);
    }

    private void resolveFromToken(Jwt jwt, HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
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

        observeHeaderMismatch(request, tenantId);

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

    /** The token wins; a mismatching {@code X-Tenant-Id} header is recorded, not honoured. */
    private void observeHeaderMismatch(HttpServletRequest request, String claimTenant) {
        String header = request.getHeader(tenantHeaderName);
        if (header != null && !header.isBlank() && !header.equals(claimTenant)) {
            meterRegistry.counter(MISMATCH_METRIC).increment();
            log.warn("Tenant header/claim mismatch (claim wins): header={}, claim={}", header, claimTenant);
        }
    }

    private void permissiveHeaderFallback(HttpServletRequest request, HttpServletResponse response,
                                          FilterChain filterChain) throws ServletException, IOException {
        String headerTenant = request.getHeader(tenantHeaderName);
        if (headerTenant == null || headerTenant.isBlank()) {
            count("absent");
            filterChain.doFilter(request, response);
            return;
        }
        if (!isTrustedSource(request)) {
            count("blocked_untrusted");
            log.debug("PERMISSIVE header fallback ignored: {} not a trusted source", request.getRemoteAddr());
            filterChain.doFilter(request, response);
            return;
        }
        try {
            UUID.fromString(headerTenant);
        } catch (IllegalArgumentException e) {
            count("blocked_invalid");
            filterChain.doFilter(request, response);
            return;
        }

        count("applied");
        try {
            TenantContextHolder.set(TenantContext.of(headerTenant, headerTenant));
            MDC.put(MDC_TENANT, headerTenant);
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            MDC.remove(MDC_TENANT);
        }
    }

    private boolean isTrustedSource(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote != null && trustedFallbackMatchers.stream().anyMatch(m -> m.matches(remote));
    }

    private void count(String outcome) {
        meterRegistry.counter(FALLBACK_METRIC, "outcome", outcome).increment();
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
