package com.originex.los.integration;

import com.originex.los.application.port.out.CustomerVerificationPort;
import com.originex.los.application.port.out.CustomerVerificationPort.CustomerEligibility;
import com.originex.testsupport.keycloak.KeycloakSupport;
import com.originex.testsupport.rls.RlsPostgresSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * los-service's Phase-2 canary proof: tenant isolation holds when the tenant comes
 * from a <b>verified JWT claim</b>. los's first boot-level test — before this,
 * nothing booted the service, which is how its missing {@code inbox_events} table
 * ({@code 8b11d5f}) and its 500-instead-of-404 mapping ({@code fbc7a82}) both
 * survived: its unit tests never load a Spring context.
 *
 * <p><b>What this test does NOT cover.</b> los is the first canary whose write path
 * is not self-contained: {@code submitApplication} calls
 * {@link CustomerVerificationPort} for an eligibility check before it writes.
 * That port is mocked here, so los's outbound REST adapters and their resilience4j
 * circuit-breaker / retry / fallback behaviour are <b>not exercised by this
 * test</b> — they deserve their own, and a green run here says nothing about them.
 * Everything actually under test stays real: the controller, the security filter
 * chain, {@code TenantClaimResolutionFilter}, the RLS transaction manager, the
 * {@code originex_app} datasource, the repository, and the outbox write.
 *
 * <p>Mocking the port (rather than stubbing HTTP) is deliberate: the canary's
 * question is whether a verified claim scopes RLS correctly, and the collaborator
 * is not part of that question. Stubbing HTTP would test los's integration
 * behaviour too — a different scope, and one that belongs in its own test.
 *
 * <p>Like payment's and unlike ledger's, this canary <b>does</b> exercise the
 * transactional outbox: {@code submitApplication} publishes before returning, so
 * the {@code String}→{@code jsonb} bind is proven on the RLS datasource here too.
 *
 * <p>Tokens are minted for the realm's {@code customer-alice} ({@link #ALICE_TENANT})
 * and {@code customer-bob} ({@link #BOB_TENANT}), and now request
 * {@code applications:submit applications:read} — the scopes for the two operations
 * this test drives. That is load-bearing: the port is guarded as of the authz pass,
 * so under {@code ENFORCED} a scopeless token would earn <b>403</b> on the submit,
 * not the 202 asserted below. A green run therefore also proves the guards are wired
 * to the real filter chain and satisfied by a correctly-scoped token, not merely
 * that RLS isolates tenants.
 *
 * <p>The read path builds {@code /v1/loan-applications/{id}} directly, as the other
 * canaries do. Unlike payment's, los's {@code Location} header <i>does</i> resolve —
 * verified against a running service rather than assumed from the routes nesting
 * correctly — so following it would also have worked here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                // This sliced context excludes Redis and runs off-Kubernetes, so the app's
                // health groups reference contributors that aren't present here.
                "management.endpoint.health.validate-group-membership=false"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@Tag("keycloak")
@DisplayName("LOS HTTP path — RLS tenant isolation driven by verified JWT claims (Testcontainers)")
class LosRlsJwtIsolationIntegrationTest {

    /** customer-alice's tenant in infra/keycloak/realm-export.json. */
    private static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";
    /** customer-bob's tenant in infra/keycloak/realm-export.json. */
    private static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String WEB_CLIENT = "originex-web";
    private static final String PASSWORD = "password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_los");

    @Container
    static final GenericContainer<?> KEYCLOAK = KeycloakSupport.newContainer();

    private static String aliceToken;
    private static String bobToken;

    /**
     * The only collaborator on the submit path. Real behaviour would call
     * customer-service over HTTP; that is not what this canary asks about.
     *
     * <p>{@code @MockitoBean}, not Boot's {@code @MockBean} — the latter is
     * deprecated and marked for removal in Boot 3.4.
     */
    @MockitoBean
    CustomerVerificationPort customerVerificationPort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // The rls profile derives the app/system/owner URLs from spring.datasource.url;
        // the superuser credentials here only resolve that fallback.
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        // Turn security on: TenantResolutionFilter (header) drops out and
        // TenantClaimResolutionFilter (claim) takes over. Mode defaults to ENFORCED.
        r.add("originex.security.enabled", () -> "true");
        r.add("originex.security.issuer-uri", () -> KeycloakSupport.issuerUri(KEYCLOAK));
    }

    @BeforeAll
    static void mintTokens() {
        aliceToken = token("customer-alice");
        bobToken = token("customer-bob");
    }

    @BeforeEach
    void customerIsEligible() {
        // exists && kycVerified => isEligible(). Without this the service throws
        // IllegalStateException -> 422 before ever reaching the database.
        when(customerVerificationPort.verifyCustomerEligibility(anyString(), anyString()))
                .thenReturn(new CustomerEligibility(true, true, "Alice", "ok"));
    }

    @Autowired
    TestRestTemplate rest;

    /**
     * Reads as {@code originex_owner} (BYPASSRLS): the outbox assertion is about
     * whether the row reached the database at all, so it must not itself be subject
     * to RLS or to whether a tenant context is set on the reading thread.
     */
    private static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
    }

    @Test
    @DisplayName("an application submitted by alice's tenant is invisible to bob's tenant")
    void jwtClaimScopesReadsToTheCallersTenant() {
        UUID applicationId = submitAs(aliceToken, UUID.randomUUID());

        assertThat(getAs(aliceToken, applicationId, null).getStatusCode())
                .as("alice reads back the application her own tenant submitted")
                .isEqualTo(HttpStatus.OK);

        assertThat(getAs(bobToken, applicationId, null).getStatusCode())
                .as("bob's tenant is blocked by RLS — loan_applications row not visible to originex_app under his tenant")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a spoofed X-Tenant-Id cannot override the verified tenant claim")
    void spoofedTenantHeaderIsIgnored() {
        UUID applicationId = submitAs(aliceToken, UUID.randomUUID());

        // Bob presents his own token but spoofs alice's tenant in the header. If the
        // header were honoured this would return 200 — under ENFORCED it is ignored,
        // so RLS still scopes him to his own tenant.
        assertThat(getAs(bobToken, applicationId, ALICE_TENANT).getStatusCode())
                .as("header is ignored under ENFORCED; the claim wins and RLS hides the row")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The same in the other direction: a misleading header does not lock alice out
        // of her own tenant's data either.
        assertThat(getAs(aliceToken, applicationId, BOB_TENANT).getStatusCode())
                .as("alice still sees her own row; her claim, not the header, decides")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the transactional outbox write succeeds on the RLS datasource, tagged with the claim's tenant")
    void outboxWriteSucceedsUnderRlsAndCarriesTheClaimTenant() {
        // Guards the String->jsonb bind (SQLSTATE 42804) on the RLS pools. A silently
        // missing outbox event is exactly how that defect hid: the listener retries and
        // drops it, with no DLQ — so assert the row, not just the 201.
        UUID applicationId = submitAs(aliceToken, UUID.randomUUID());

        JdbcTemplate owner = ownerJdbc();
        String tenantId = owner.queryForObject(
                "select tenant_id::text from outbox_events where aggregate_id = ?", String.class, applicationId);
        assertThat(tenantId)
                .as("outbox row exists and carries the tenant from alice's JWT claim, not a header")
                .isEqualTo(ALICE_TENANT);

        String metadataType = owner.queryForObject(
                "select pg_typeof(metadata)::text from outbox_events where aggregate_id = ?",
                String.class, applicationId);
        assertThat(metadataType)
                .as("metadata really is jsonb — a raw-JSON String was accepted, not rejected as varchar")
                .isEqualTo("jsonb");
    }

    @Test
    @DisplayName("an unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // A tenant header alone must not buy access under ENFORCED.
        headers.set("X-Tenant-Id", ALICE_TENANT);

        ResponseEntity<String> response = rest.exchange(
                "/v1/loan-applications", HttpMethod.POST,
                new HttpEntity<>(body(UUID.randomUUID()), headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("no token under ENFORCED → 401, regardless of X-Tenant-Id")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──

    private static String token(String username) {
        // Requests exactly the scopes this test exercises — submit (POST) and read (GET).
        // Under ENFORCED the guarded port would 403 the submit without them; tenant_id
        // still rides on the originex-tenant default client scope independently.
        return KeycloakSupport.passwordToken(KEYCLOAK, WEB_CLIENT, username, PASSWORD,
                "openid", "applications:submit", "applications:read");
    }

    /**
     * Submits an application as the token's tenant and returns the new id.
     *
     * <p>los answers <b>202 Accepted</b>, not 201 Created — {@code ResponseEntity.accepted()},
     * deliberately: submission starts an asynchronous underwriting workflow rather than
     * completing a resource. That differs from customer/ledger/payment, whose canaries
     * all assert 201.
     */
    private UUID submitAs(String token, UUID customerId) {
        ResponseEntity<ApplicationRef> accepted = rest.exchange(
                "/v1/loan-applications", HttpMethod.POST,
                new HttpEntity<>(body(customerId), bearerJson(token)),
                ApplicationRef.class);
        assertThat(accepted.getStatusCode()).as("submission is accepted for an authenticated, eligible caller")
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).isNotNull();
        assertThat(accepted.getBody().id()).as("submitted application's id").isNotNull();
        return accepted.getBody().id();
    }

    /** GETs the application; {@code spoofedTenant} may be null. */
    private ResponseEntity<String> getAs(String token, UUID applicationId, String spoofedTenant) {
        HttpHeaders headers = bearer(token);
        if (spoofedTenant != null) {
            headers.set("X-Tenant-Id", spoofedTenant);
        }
        return rest.exchange("/v1/loan-applications/" + applicationId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    /** A distinct customerId per call keeps the 30-day duplicate check from rejecting reruns. */
    private static String body(UUID customerId) {
        return """
                {"customerId":"%s","productCode":"PERSONAL_LOAN","amount":"500000","currency":"INR",\
                "tenureMonths":24,"purpose":"HOME_RENOVATION","channel":"WEB","applicantName":"Alice A"}"""
                .formatted(customerId);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders bearerJson(String token) {
        HttpHeaders h = bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /**
     * Minimal view of ApplicationResponse — only the id this test needs. The field is
     * {@code id}, not {@code applicationId}: unlike ledger and payment, los names it
     * plainly on the wire.
     */
    private record ApplicationRef(UUID id) {
    }
}
