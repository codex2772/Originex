package com.originex.starter.tenant;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that resolves tenant context from incoming HTTP request headers
 * and sets it in the TenantContextHolder for the duration of the request.
 *
 * <p>Also propagates tenant_id to MDC for structured logging.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

    private final String tenantHeaderName;
    private final boolean enforce;

    public TenantResolutionFilter(String tenantHeaderName, boolean enforce) {
        this.tenantHeaderName = tenantHeaderName;
        this.enforce = enforce;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String tenantId = request.getHeader(tenantHeaderName);

        // Skip for health/actuator endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (tenantId == null || tenantId.isBlank()) {
            if (enforce) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"type\":\"https://api.originex.io/problems/missing-tenant\","
                                + "\"title\":\"Missing Tenant\","
                                + "\"status\":400,"
                                + "\"detail\":\"Header '" + tenantHeaderName + "' is required\"}");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Extract slug from path if available, otherwise use tenantId as slug
            String tenantSlug = tenantId;
            TenantContext context = TenantContext.of(tenantId, tenantSlug);
            TenantContextHolder.set(context);
            MDC.put("tenantId", tenantId);

            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
            MDC.remove("tenantId");
        }
    }
}
