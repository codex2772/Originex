package com.originex.starter.security;

import com.originex.common.security.SubjectContextHolder;
import com.originex.common.tenant.TenantContextHolder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TenantClaimResolutionFilter}: it must populate tenant and
 * subject context from verified JWT claims, reject tokens whose business claims are
 * missing/malformed, and always clear both contexts afterward (success and
 * exception paths) so a pooled virtual thread never leaks identity.
 */
@DisplayName("TenantClaimResolutionFilter")
class TenantClaimResolutionFilterTest {

    private static final String TENANT = "00000000-0000-0000-0000-00000000000a";

    private final TenantClaimResolutionFilter filter = new TenantClaimResolutionFilter();

    @AfterEach
    void clearContexts() {
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

    @Test
    @DisplayName("valid JWT: tenant and subject context are populated for the request")
    void validJwtPopulatesContexts() throws Exception {
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT).claim("customer_id", "cust-9").build());

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        AtomicReference<String> subjectDuring = new AtomicReference<>();
        AtomicReference<String> customerDuring = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> {
            tenantDuring.set(TenantContextHolder.get().tenantId());
            subjectDuring.set(SubjectContextHolder.get().subject());
            customerDuring.set(SubjectContextHolder.get().customerId());
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(tenantDuring).hasValue(TENANT);
        assertThat(subjectDuring).hasValue("user-1");
        assertThat(customerDuring).hasValue("cust-9");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("missing tenant_id claim: rejected 403, chain not invoked")
    void missingTenantClaimRejected() throws Exception {
        authenticate(jwt().subject("user-1").build());
        boolean[] chainCalled = {false};
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (rq, rs) -> chainCalled[0] = true);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chainCalled[0]).isFalse();
        assertThat(TenantContextHolder.get()).isNull();
        assertThat(SubjectContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("malformed tenant_id (not a UUID): rejected 400, chain not invoked")
    void invalidTenantUuidRejected() throws Exception {
        authenticate(jwt().subject("user-1").claim("tenant_id", "not-a-uuid").build());
        boolean[] chainCalled = {false};
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (rq, rs) -> chainCalled[0] = true);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chainCalled[0]).isFalse();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("missing subject: rejected 403, chain not invoked")
    void missingSubjectRejected() throws Exception {
        authenticate(jwt().claim("tenant_id", TENANT).build()); // no 'sub'
        boolean[] chainCalled = {false};
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (rq, rs) -> chainCalled[0] = true);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chainCalled[0]).isFalse();
        assertThat(SubjectContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("contexts cleared after a successful request")
    void contextsClearedAfterSuccess() throws Exception {
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT).build());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (rq, rs) -> {
            assertThat(TenantContextHolder.get()).isNotNull(); // set during
        });

        assertThat(TenantContextHolder.get()).as("cleared after success").isNull();
        assertThat(SubjectContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("contexts cleared even when the downstream chain throws")
    void contextsClearedAfterException() {
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT).build());
        FilterChain boom = (rq, rs) -> {
            throw new RuntimeException("downstream failure");
        };

        assertThatThrownBy(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), boom))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream failure");

        assertThat(TenantContextHolder.get()).as("cleared on exception").isNull();
        assertThat(SubjectContextHolder.get()).isNull();
    }
}
