package com.originex.starter.security;

import com.originex.common.security.SubjectContextHolder;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.starter.OriginexAutoConfiguration;
import com.originex.starter.tenant.TenantResolutionFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the tenant-resolution <b>security boundary</b> (see
 * {@code dev/AUTH_DESIGN.md} §5). The platform has two tenant sources that are
 * mutually exclusive by mode:
 *
 * <ul>
 *   <li><b>security disabled</b> (default) — the legacy {@link TenantResolutionFilter}
 *       is registered and derives tenant from the {@code X-Tenant-Id} header
 *       (today's behaviour, unchanged);</li>
 *   <li><b>security enabled</b> — the legacy header filter is <i>not</i> registered;
 *       the verified JWT is the single authoritative source of tenant/subject via
 *       {@link TenantClaimResolutionFilter}, and {@code X-Tenant-Id} is ignored.</li>
 * </ul>
 *
 * <p>The mode switch itself is asserted with a {@link WebApplicationContextRunner}
 * (bean present/absent); the JWT-authoritative and fail-closed behaviours are
 * asserted by driving the filters directly.
 *
 * <p><b>Error codes</b> (existing {@link TenantClaimResolutionFilter} convention):
 * a valid token missing the {@code tenant_id} claim → <b>403</b>; a malformed
 * {@code tenant_id} → <b>400</b>. Rejection of an <i>unauthenticated</i> request
 * (no/invalid/expired token → 401) is enforced upstream by the resource-server
 * chain in {@code SecurityAutoConfiguration} ({@code anyRequest().authenticated()}),
 * covered by {@code SecurityAutoConfigurationTest}.
 */
@DisplayName("Tenant resolution — security-mode boundary")
class TenantResolutionSecurityModeTest {

    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";
    private static final String HEADER = "X-Tenant-Id";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OriginexAutoConfiguration.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        SubjectContextHolder.clear();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256");
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    // ── 1. security disabled + X-Tenant-Id works ───────────────────────────────

    @Test
    @DisplayName("1a: security disabled → legacy header filter is registered")
    void disabledRegistersHeaderFilter() {
        contextRunner.run(ctx -> assertThat(ctx).hasBean("tenantResolutionFilter"));
        // default (property absent) also keeps the header filter:
        contextRunner.withPropertyValues("originex.security.enabled=false")
                .run(ctx -> assertThat(ctx).hasBean("tenantResolutionFilter"));
    }

    @Test
    @DisplayName("1b: legacy header filter sets tenant from X-Tenant-Id")
    void headerFilterSetsTenantWhenDisabled() throws Exception {
        TenantResolutionFilter headerFilter = new TenantResolutionFilter(HEADER, true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, TENANT_A);

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        headerFilter.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> tenantDuring.set(TenantContextHolder.get().tenantId()));

        assertThat(tenantDuring).hasValue(TENANT_A);
        assertThat(TenantContextHolder.get()).as("cleared after request").isNull();
    }

    // ── 2. security enabled + valid JWT tenant works ───────────────────────────

    @Test
    @DisplayName("2: security enabled → JWT tenant claim populates context")
    void enabledUsesJwtTenant() throws Exception {
        TenantClaimResolutionFilter filter =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, List.of(), new SimpleMeterRegistry());
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT_A).build());

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> tenantDuring.set(TenantContextHolder.get().tenantId()));

        assertThat(tenantDuring).hasValue(TENANT_A);
    }

    // ── 3. security enabled + header only → header is NOT a tenant source ───────

    @Test
    @DisplayName("3a: security enabled → legacy header filter is NOT registered")
    void enabledDropsHeaderFilter() {
        contextRunner.withPropertyValues("originex.security.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("tenantResolutionFilter"));
    }

    @Test
    @DisplayName("3b: security enabled + header only (no token) → header ignored, no tenant context")
    void enabledIgnoresHeaderWithoutToken() throws Exception {
        TenantClaimResolutionFilter filter =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, List.of(), new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest(); // no authentication in SecurityContext
        request.addHeader(HEADER, TENANT_A);

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> tenantDuring.set(TenantContextHolder.get() == null
                        ? null : TenantContextHolder.get().tenantId()));

        // Fail-closed: the header is never a tenant source when security is enabled.
        assertThat(tenantDuring.get()).isNull();
    }

    // ── 4. JWT/header mismatch → JWT wins (+ mismatch counter) ──────────────────

    @Test
    @DisplayName("4: JWT tenant != X-Tenant-Id header → JWT wins, mismatch counter increments")
    void jwtWinsOnMismatch() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TenantClaimResolutionFilter filter =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, List.of(), registry);
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT_A).build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, TENANT_B); // spoofed header

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> tenantDuring.set(TenantContextHolder.get().tenantId()));

        assertThat(tenantDuring).as("JWT tenant wins over header").hasValue(TENANT_A);
        assertThat(registry.counter(TenantClaimResolutionFilter.MISMATCH_METRIC).count()).isEqualTo(1.0d);
    }

    // ── 5. missing tenant claim → 403 (existing convention) ────────────────────

    @Test
    @DisplayName("5: security enabled + token missing tenant_id → 403, chain not invoked")
    void missingTenantClaimRejected403() throws Exception {
        TenantClaimResolutionFilter filter =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, List.of(), new SimpleMeterRegistry());
        authenticate(jwt().subject("user-1").build()); // no tenant_id
        boolean[] chainCalled = {false};
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (rq, rs) -> chainCalled[0] = true);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chainCalled[0]).isFalse();
        assertThat(TenantContextHolder.get()).isNull();
    }

    // ── 6. malformed tenant UUID → 400 (existing convention) ───────────────────

    @Test
    @DisplayName("6: security enabled + malformed tenant_id → 400, chain not invoked")
    void malformedTenantUuidRejected400() throws Exception {
        TenantClaimResolutionFilter filter =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, List.of(), new SimpleMeterRegistry());
        authenticate(jwt().subject("user-1").claim("tenant_id", "not-a-uuid").build());
        boolean[] chainCalled = {false};
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (rq, rs) -> chainCalled[0] = true);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chainCalled[0]).isFalse();
        assertThat(TenantContextHolder.get()).isNull();
    }

    // ── 7. context cleanup after request (no cross-request leakage) ────────────

    @Test
    @DisplayName("7: both tenant and subject context are cleared after the request")
    void contextsClearedAfterRequest() throws Exception {
        TenantClaimResolutionFilter filter =
                new TenantClaimResolutionFilter(AuthMode.ENFORCED, HEADER, List.of(), new SimpleMeterRegistry());
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT_A).claim("customer_id", "cust-9").build());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (rq, rs) -> {
            assertThat(TenantContextHolder.get()).as("set during request").isNotNull();
            assertThat(SubjectContextHolder.get()).as("set during request").isNotNull();
        });

        assertThat(TenantContextHolder.get()).as("tenant cleared").isNull();
        assertThat(SubjectContextHolder.get()).as("subject cleared").isNull();
    }
}
