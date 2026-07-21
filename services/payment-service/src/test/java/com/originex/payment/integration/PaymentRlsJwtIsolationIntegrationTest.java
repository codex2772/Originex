package com.originex.payment.integration;

import com.originex.testsupport.keycloak.KeycloakSupport;
import com.originex.testsupport.rls.RlsPostgresSupport;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment-service's Phase-2 canary proof: tenant isolation holds when the tenant
 * comes from a <b>verified JWT claim</b>. payment's first boot-level test — before
 * this, nothing exercised the service under {@code rls}, and nothing booted it at
 * all (which is how the schema defect fixed in {@code 1bf5fd0} survived: its unit
 * tests never load a Spring context).
 *
 * <p>Modelled on {@code CustomerRlsJwtIsolationIntegrationTest} and
 * {@code LedgerRlsJwtIsolationIntegrationTest}; see {@code dev/RLS_ENABLEMENT.md}
 * for why a header-driven test would prove nothing about the authenticated path.
 *
 * <p><b>This test carries weight ledger's cannot.</b> Ledger's account path is
 * DB-only, so it never touches the transactional outbox. {@code initiateDisbursement}
 * <i>does</i> — the class is {@code @Transactional}, so a 201 means the outbox
 * insert committed in the same transaction. That exercises the
 * {@code String}→{@code jsonb} bind on the <b>RLS</b> datasources, the defect
 * {@code 84e584e}/{@code 51d6246} fixed and the one that silently dropped records
 * platform-wide. The outbox assertion below is deliberate, not incidental.
 *
 * <p>Kafka and Redis autoconfiguration are excluded: the outbox write is a plain
 * insert in the request transaction (the poller ships it to Kafka separately), and
 * all four rail adapters are sandbox with no HTTP client — so the test needs only
 * one Postgres and one Keycloak.
 *
 * <p><b>Reads {@code paymentOrderId} from the response body rather than following
 * the {@code Location} header.</b> That is a workaround, not a preference:
 * {@code POST /v1/payments/disbursements} returns a Location of
 * {@code /v1/payments/disbursements/{id}}, but the resource lives at
 * {@code /v1/payments/{id}} — following it yields 500. Tracked as KI-7 in
 * {@code dev/KNOWN_ISSUES.md}; revert to Location-following once fixed.
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
@DisplayName("Payment HTTP path — RLS tenant isolation driven by verified JWT claims (Testcontainers)")
class PaymentRlsJwtIsolationIntegrationTest {

    /** customer-alice's tenant in infra/keycloak/realm-export.json. */
    private static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";
    /** customer-bob's tenant in infra/keycloak/realm-export.json. */
    private static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String WEB_CLIENT = "originex-web";
    private static final String PASSWORD = "password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_payment");

    @Container
    static final GenericContainer<?> KEYCLOAK = KeycloakSupport.newContainer();

    private static String aliceToken;
    private static String bobToken;

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

    @Autowired
    TestRestTemplate rest;

    /**
     * Reads as {@code originex_owner} (BYPASSRLS) rather than autowiring the app's
     * routing datasource: this assertion is about whether the row reached the
     * database at all, so it must not itself be subject to RLS or to whether a
     * tenant context happens to be set on the reading thread.
     */
    private static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
    }

    @Test
    @DisplayName("a disbursement initiated by alice's tenant is invisible to bob's tenant")
    void jwtClaimScopesReadsToTheCallersTenant() {
        UUID paymentOrderId = disburseAs(aliceToken, "44444444-4444-4444-4444-444444444441");

        assertThat(getAs(aliceToken, paymentOrderId, null).getStatusCode())
                .as("alice reads back the payment order her own tenant created")
                .isEqualTo(HttpStatus.OK);

        assertThat(getAs(bobToken, paymentOrderId, null).getStatusCode())
                .as("bob's tenant is blocked by RLS — payment_orders row not visible to originex_app under his tenant")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a spoofed X-Tenant-Id cannot override the verified tenant claim")
    void spoofedTenantHeaderIsIgnored() {
        UUID paymentOrderId = disburseAs(aliceToken, "44444444-4444-4444-4444-444444444442");

        // Bob presents his own token but spoofs alice's tenant in the header. If the
        // header were honoured this would return 200 — under ENFORCED it is ignored,
        // so RLS still scopes him to his own tenant.
        assertThat(getAs(bobToken, paymentOrderId, ALICE_TENANT).getStatusCode())
                .as("header is ignored under ENFORCED; the claim wins and RLS hides the row")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The same in the other direction: a misleading header does not lock alice out
        // of her own tenant's data either.
        assertThat(getAs(aliceToken, paymentOrderId, BOB_TENANT).getStatusCode())
                .as("alice still sees her own row; her claim, not the header, decides")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the transactional outbox write succeeds on the RLS datasource, tagged with the claim's tenant")
    void outboxWriteSucceedsUnderRlsAndCarriesTheClaimTenant() {
        // Guards the String->jsonb bind (SQLSTATE 42804) on the RLS pools specifically.
        // PaymentApplicationService is @Transactional, so a 201 already implies the
        // insert committed — but assert the row, since a silently-missing outbox event
        // is exactly how this defect hid: the listener retries and drops it, with no DLQ.
        UUID paymentOrderId = disburseAs(aliceToken, "44444444-4444-4444-4444-444444444443");

        JdbcTemplate owner = ownerJdbc();
        String tenantId = owner.queryForObject(
                "select tenant_id::text from outbox_events where aggregate_id = ?", String.class, paymentOrderId);
        assertThat(tenantId)
                .as("outbox row exists and carries the tenant from alice's JWT claim, not a header")
                .isEqualTo(ALICE_TENANT);

        String metadataType = owner.queryForObject(
                "select pg_typeof(metadata)::text from outbox_events where aggregate_id = ?",
                String.class, paymentOrderId);
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
                "/v1/payments/disbursements", HttpMethod.POST,
                new HttpEntity<>(body("44444444-4444-4444-4444-444444444449"), headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("no token under ENFORCED → 401, regardless of X-Tenant-Id")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──

    private static String token(String username) {
        // No scopes requested: payment has no @PreAuthorize, and tenant_id rides on the
        // originex-tenant default client scope.
        return KeycloakSupport.passwordToken(KEYCLOAK, WEB_CLIENT, username, PASSWORD, "openid");
    }

    /** Initiates a disbursement as the token's tenant and returns the new order's id. */
    private UUID disburseAs(String token, String loanId) {
        ResponseEntity<PaymentOrderRef> created = rest.exchange(
                "/v1/payments/disbursements", HttpMethod.POST,
                new HttpEntity<>(body(loanId), bearerJson(token)),
                PaymentOrderRef.class);
        assertThat(created.getStatusCode()).as("disbursement is accepted for an authenticated caller")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().paymentOrderId()).as("created order's id").isNotNull();
        return created.getBody().paymentOrderId();
    }

    /** GETs the order at its real path; {@code spoofedTenant} may be null. */
    private ResponseEntity<String> getAs(String token, UUID paymentOrderId, String spoofedTenant) {
        HttpHeaders headers = bearer(token);
        if (spoofedTenant != null) {
            headers.set("X-Tenant-Id", spoofedTenant);
        }
        return rest.exchange("/v1/payments/" + paymentOrderId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private static String body(String loanId) {
        return """
                {"loanId":"%s","customerId":"33333333-3333-3333-3333-333333333333",\
                "amount":"5000.00","currency":"INR","beneficiaryAccountNumber":"1234567890",\
                "beneficiaryIfsc":"SBIN0001234","beneficiaryName":"Test Borrower",\
                "beneficiaryBankName":"SBI"}""".formatted(loanId);
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

    /** Minimal view of PaymentOrderResponse — only the id this test needs. */
    private record PaymentOrderRef(UUID paymentOrderId) {
    }
}
