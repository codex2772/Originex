package com.originex.starter.security;

import com.originex.common.security.SubjectContext;
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
 * Unit tests for {@link TenantClaimResolutionFilter}: it must classify the
 * principal (human / customer / service account), populate tenant and subject
 * context from verified JWT claims, reject tokens whose business claims are
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
    @DisplayName("valid customer JWT: tenant + CUSTOMER subject context are populated")
    void validCustomerJwtPopulatesContexts() throws Exception {
        authenticate(jwt().subject("user-1").claim("tenant_id", TENANT).claim("customer_id", "cust-9").build());

        AtomicReference<String> tenantDuring = new AtomicReference<>();
        AtomicReference<SubjectContext> subjectDuring = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> {
            tenantDuring.set(TenantContextHolder.get().tenantId());
            subjectDuring.set(SubjectContextHolder.get());
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(tenantDuring).hasValue(TENANT);
        assertThat(subjectDuring.get().type()).isEqualTo(SubjectContext.PrincipalType.CUSTOMER);
        assertThat(subjectDuring.get().subject()).isEqualTo("user-1");
        assertThat(subjectDuring.get().customerId()).isEqualTo("cust-9");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("service-account JWT (client id, no sub): classified as SERVICE_ACCOUNT")
    void serviceAccountJwtClassifiedAsMachine() throws Exception {
        authenticate(jwt().claim("azp", "svc-los").claim("tenant_id", TENANT).build());

        AtomicReference<SubjectContext> subjectDuring = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (rq, rs) -> subjectDuring.set(SubjectContextHolder.get()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(subjectDuring.get().type()).isEqualTo(SubjectContext.PrincipalType.SERVICE_ACCOUNT);
        assertThat(subjectDuring.get().subject()).isEqualTo("svc-los");
        assertThat(subjectDuring.get().isMachine()).isTrue();
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
    @DisplayName("no usable identity (no sub, no client id): rejected 403, chain not invoked")
    void tokenWithNoIdentityRejected() throws Exception {
        authenticate(jwt().claim("tenant_id", TENANT).build()); // no 'sub', no 'azp'/'client_id'
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
            assertThat(SubjectContextHolder.get().type()).isEqualTo(SubjectContext.PrincipalType.HUMAN_USER);
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
