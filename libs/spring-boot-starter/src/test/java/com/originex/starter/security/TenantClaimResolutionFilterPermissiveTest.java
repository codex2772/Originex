package com.originex.starter.security;

import com.originex.common.security.SubjectContextHolder;
import com.originex.common.tenant.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERMISSIVE-mode behaviour of {@link TenantClaimResolutionFilter}: the
 * network-restricted {@code X-Tenant-Id} fallback when no token is present, and the
 * claim/header mismatch observation. Both are metered (dev/AUTH_DESIGN.md §8.2).
 */
@DisplayName("TenantClaimResolutionFilter — PERMISSIVE mode")
class TenantClaimResolutionFilterPermissiveTest {

    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";
    private static final String OTHER_TENANT = "00000000-0000-0000-0000-00000000000b";
    private static final String HEADER = "X-Tenant-Id";
    private static final List<String> TRUSTED = List.of("10.0.0.0/8");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        SubjectContextHolder.clear();
    }

    private TenantClaimResolutionFilter permissive(List<String> cidrs) {
        return new TenantClaimResolutionFilter(AuthMode.PERMISSIVE, HEADER, cidrs, registry);
    }

    private static MockHttpServletRequest requestFrom(String remoteAddr, String tenantHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        if (tenantHeader != null) {
            req.addHeader(HEADER, tenantHeader);
        }
        return req;
    }

    private double fallback(String outcome) {
        return registry.get(TenantClaimResolutionFilter.FALLBACK_METRIC).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("no token + header from a trusted network: fallback applied")
    void fallbackAppliedFromTrustedNetwork() throws Exception {
        AtomicReference<String> tenantDuring = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> tenantDuring.set(
                TenantContextHolder.get() == null ? null : TenantContextHolder.get().tenantId());

        permissive(TRUSTED).doFilter(requestFrom("10.1.2.3", TENANT), new MockHttpServletResponse(), chain);

        assertThat(tenantDuring).hasValue(TENANT);
        assertThat(fallback("applied")).isEqualTo(1.0);
        assertThat(TenantContextHolder.get()).as("cleared after").isNull();
    }

    @Test
    @DisplayName("no token + header from an untrusted network: fallback blocked, request proceeds anonymously")
    void fallbackBlockedFromUntrustedNetwork() throws Exception {
        AtomicReference<Object> tenantDuring = new AtomicReference<>("sentinel");
        FilterChain chain = (rq, rs) -> tenantDuring.set(TenantContextHolder.get());

        permissive(TRUSTED).doFilter(requestFrom("8.8.8.8", TENANT), new MockHttpServletResponse(), chain);

        assertThat(tenantDuring.get()).isNull();
        assertThat(fallback("blocked_untrusted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("empty trusted-CIDR allowlist (default): fallback is fail-closed even with a header")
    void fallbackFailClosedByDefault() throws Exception {
        permissive(List.of()).doFilter(requestFrom("10.1.2.3", TENANT), new MockHttpServletResponse(),
                (rq, rs) -> assertThat(TenantContextHolder.get()).isNull());

        assertThat(fallback("blocked_untrusted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no token + no header: no context, counted as absent")
    void noHeaderNoContext() throws Exception {
        permissive(TRUSTED).doFilter(requestFrom("10.1.2.3", null), new MockHttpServletResponse(),
                (rq, rs) -> assertThat(TenantContextHolder.get()).isNull());

        assertThat(fallback("absent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no token + trusted network but malformed header tenant: blocked_invalid")
    void malformedHeaderBlocked() throws Exception {
        permissive(TRUSTED).doFilter(requestFrom("10.1.2.3", "not-a-uuid"), new MockHttpServletResponse(),
                (rq, rs) -> assertThat(TenantContextHolder.get()).isNull());

        assertThat(fallback("blocked_invalid")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("token present with a mismatching X-Tenant-Id header: claim wins, mismatch counted")
    void claimWinsOverMismatchingHeader() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("user-1").claim("tenant_id", TENANT).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        permissive(TRUSTED).doFilter(requestFrom("10.1.2.3", OTHER_TENANT), new MockHttpServletResponse(),
                (rq, rs) -> tenantDuring.set(TenantContextHolder.get().tenantId()));

        assertThat(tenantDuring).as("claim wins").hasValue(TENANT);
        assertThat(registry.get(TenantClaimResolutionFilter.MISMATCH_METRIC).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ENFORCED mode never applies the header fallback")
    void enforcedNeverFallsBack() throws Exception {
        TenantClaimResolutionFilter enforced =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, TRUSTED, registry);

        enforced.doFilter(requestFrom("10.1.2.3", TENANT), new MockHttpServletResponse(),
                (rq, rs) -> assertThat(TenantContextHolder.get()).isNull());

        assertThat(registry.find(TenantClaimResolutionFilter.FALLBACK_METRIC).counter()).isNull();
    }
}
