package com.originex.ledger.integration;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ledger-service's Phase-2 canary proof: tenant isolation holds when the tenant
 * comes from a <b>verified JWT claim</b>. Ledger's first boot-level test — before
 * this, nothing exercised the service under {@code rls} at all.
 *
 * <p>Modelled on {@code CustomerRlsJwtIsolationIntegrationTest}; see
 * {@code dev/RLS_ENABLEMENT.md} for why a header-driven test would prove nothing
 * about the authenticated path ({@code TenantResolutionFilter} is registered only
 * while {@code originex.security.enabled=false}, so it does not exist here).
 *
 * <p><b>Surface.</b> {@code POST /v1/ledger/accounts} is DB-only: it saves through
 * {@code AccountPersistenceAdapter} and returns, with no outbox write and no Kafka
 * publish (unlike {@code POST /v1/ledger/journal-entries}, which does publish via
 * {@code OutboxPublisher}). That is why Kafka and Redis autoconfiguration can be
 * excluded here and the test stays to one Postgres plus one Keycloak.
 *
 * <p>{@code AccountJpaEntity} maps to {@code account_snapshots}, which carries the
 * RLS policies — so a row created under alice's claim is genuinely policy-scoped,
 * and the 404 below is RLS acting, not a missing row.
 *
 * <p>Tokens are minted for the realm's {@code customer-alice} ({@link #ALICE_TENANT})
 * and {@code customer-bob} ({@link #BOB_TENANT}) from the real
 * {@code infra/keycloak/realm-export.json}. No scopes are requested: ledger has no
 * {@code @PreAuthorize} guards, and {@code originex-tenant} is a default client
 * scope, so the tokens carry {@code tenant_id} regardless. Adding
 * {@code ledger:read}/{@code ledger:post} authorities is separate Phase-1 authz
 * work and is deliberately not folded in here.
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
@DisplayName("Ledger HTTP path — RLS tenant isolation driven by verified JWT claims (Testcontainers)")
class LedgerRlsJwtIsolationIntegrationTest {

    /** customer-alice's tenant in infra/keycloak/realm-export.json. */
    private static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";
    /** customer-bob's tenant in infra/keycloak/realm-export.json. */
    private static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String WEB_CLIENT = "originex-web";
    private static final String PASSWORD = "password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_ledger");

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

    @Test
    @DisplayName("an account opened by alice's tenant is invisible to bob's tenant")
    void jwtClaimScopesReadsToTheCallersTenant() {
        URI location = openAccountAs(aliceToken, "1001000001");

        ResponseEntity<String> asAlice = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(bearer(aliceToken)), String.class);
        assertThat(asAlice.getStatusCode())
                .as("alice reads back the account her own tenant opened")
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> asBob = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(bearer(bobToken)), String.class);
        assertThat(asBob.getStatusCode())
                .as("bob's tenant is blocked by RLS — account_snapshots row not visible to originex_app under his tenant")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a spoofed X-Tenant-Id cannot override the verified tenant claim")
    void spoofedTenantHeaderIsIgnored() {
        URI location = openAccountAs(aliceToken, "1001000002");

        // Bob presents his own token but spoofs alice's tenant in the header. If the
        // header were honoured this would return 200 — under ENFORCED it is ignored,
        // so RLS still scopes him to his own tenant.
        HttpHeaders spoofed = bearer(bobToken);
        spoofed.set("X-Tenant-Id", ALICE_TENANT);
        ResponseEntity<String> asBob = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(spoofed), String.class);
        assertThat(asBob.getStatusCode())
                .as("header is ignored under ENFORCED; the claim wins and RLS hides the row")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The same in the other direction: a misleading header does not lock alice out
        // of her own tenant's data either.
        HttpHeaders misleading = bearer(aliceToken);
        misleading.set("X-Tenant-Id", BOB_TENANT);
        ResponseEntity<String> asAlice = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(misleading), String.class);
        assertThat(asAlice.getStatusCode())
                .as("alice still sees her own row; her claim, not the header, decides")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // A tenant header alone must not buy access under ENFORCED.
        headers.set("X-Tenant-Id", ALICE_TENANT);

        ResponseEntity<String> resp = rest.exchange(
                "/v1/ledger/accounts", HttpMethod.POST,
                new HttpEntity<>(body("1001000003"), headers),
                String.class);

        assertThat(resp.getStatusCode())
                .as("no token under ENFORCED → 401, regardless of X-Tenant-Id")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──

    private static String token(String username) {
        // No scopes requested: ledger has no @PreAuthorize, and tenant_id rides on the
        // originex-tenant default client scope.
        return KeycloakSupport.passwordToken(KEYCLOAK, WEB_CLIENT, username, PASSWORD, "openid");
    }

    /** Opens an account as the token's tenant and returns its Location. */
    private URI openAccountAs(String token, String accountNumber) {
        ResponseEntity<Void> created = rest.exchange(
                "/v1/ledger/accounts", HttpMethod.POST,
                new HttpEntity<>(body(accountNumber), bearerJson(token)),
                Void.class);
        assertThat(created.getStatusCode()).as("account opens for an authenticated caller")
                .isEqualTo(HttpStatus.CREATED);
        URI location = created.getHeaders().getLocation();
        assertThat(location).as("Location of the opened account").isNotNull();
        return location;
    }

    private static String body(String accountNumber) {
        return """
                {"accountNumber":"%s","name":"Loan Principal Receivable",\
                "accountType":"ASSET","glCode":"1100","currency":"INR"}""".formatted(accountNumber);
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
}
