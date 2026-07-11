package com.originex.customer.integration;

import com.originex.testsupport.rls.RlsPostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App-level (Layer 2) proof that the HTTP path enforces tenant isolation once RLS
 * is enabled: {@code TenantResolutionFilter} sets tenant context from the
 * {@code X-Tenant-Id} header before the transaction, the RLS transaction manager
 * applies {@code app.tenant_id}, and a request from another tenant cannot read the
 * row. Runs the real customer-service with {@code @ActiveProfiles("rls")}, so it
 * connects as {@code originex_app} and migrates as {@code originex_owner} via the
 * shared harness — no superuser shortcut.
 *
 * <p>Kafka and Redis autoconfiguration are excluded: registration writes to the
 * transactional outbox (DB only), so the poller/broker are not needed and the
 * test stays to a single Postgres container. The 404-for-other-tenant assertion
 * would fail if the app were (wrongly) connected as a superuser, so it doubles as
 * proof that RLS is actually in force.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Customer HTTP path — RLS tenant isolation via X-Tenant-Id (Testcontainers)")
class CustomerHttpRlsIsolationIntegrationTest {

    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_customer");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // The rls profile derives app/system/owner URLs from spring.datasource.url;
        // the superuser username/password here are only used to resolve that
        // fallback — the app connects as originex_app, Flyway as originex_owner.
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("a customer registered by tenant A is invisible to tenant B over HTTP")
    void httpRequestsAreTenantScoped() {
        // Tenant A registers a customer.
        ResponseEntity<Void> created = rest.exchange(
                "/v1/customers", HttpMethod.POST,
                new HttpEntity<>("{\"firstName\":\"Alice\",\"lastName\":\"A\",\"phone\":\"9990000001\"}",
                        jsonHeaders(TENANT_A)),
                Void.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = created.getHeaders().getLocation();
        assertThat(location).as("Location of the created customer").isNotNull();

        // Tenant A can read it back.
        ResponseEntity<String> asA = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(tenantHeaders(TENANT_A)), String.class);
        assertThat(asA.getStatusCode()).as("owner tenant reads its own customer").isEqualTo(HttpStatus.OK);

        // Tenant B cannot — RLS hides the row, so the lookup 404s.
        ResponseEntity<String> asB = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(tenantHeaders(TENANT_B)), String.class);
        assertThat(asB.getStatusCode()).as("other tenant is blocked by RLS").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a request with no X-Tenant-Id header is rejected")
    void requestWithoutTenantHeaderIsRejected() {
        ResponseEntity<String> resp = rest.exchange(
                "/v1/customers", HttpMethod.POST,
                new HttpEntity<>("{\"firstName\":\"X\",\"lastName\":\"Y\",\"phone\":\"9990000099\"}",
                        jsonHeaders(null)),
                String.class);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ── helpers ──

    private static HttpHeaders tenantHeaders(String tenantId) {
        HttpHeaders h = new HttpHeaders();
        if (tenantId != null) {
            h.set("X-Tenant-Id", tenantId);
        }
        return h;
    }

    private static HttpHeaders jsonHeaders(String tenantId) {
        HttpHeaders h = tenantHeaders(tenantId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
