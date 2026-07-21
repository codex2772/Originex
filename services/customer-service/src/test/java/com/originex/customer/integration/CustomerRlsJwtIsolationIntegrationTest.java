package com.originex.customer.integration;

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
 * The Phase-2 gate: proves tenant isolation holds when the tenant comes from a
 * <b>verified JWT claim</b> rather than a trusted header.
 *
 * <p>Why this exists alongside {@link CustomerHttpRlsIsolationIntegrationTest}:
 * that test drives {@code TenantResolutionFilter}, which the starter registers
 * only while {@code originex.security.enabled=false}. Turning security on
 * removes that filter entirely and hands tenant resolution to
 * {@code TenantClaimResolutionFilter}, which reads the {@code tenant_id} claim.
 * The two tests therefore exercise different, mutually exclusive code paths —
 * the header test cannot say anything about the authenticated configuration
 * that production will actually run.
 *
 * <p>Runs the real service against a Postgres provisioned with the RLS roles and
 * a Keycloak importing the real {@code infra/keycloak/realm-export.json}, under
 * {@code @ActiveProfiles("rls")} (app connects as {@code originex_app}, Flyway as
 * {@code originex_owner}) and {@code originex.security.enabled=true}. The default
 * {@code AuthMode} is {@code ENFORCED}: authentication is required and
 * {@code X-Tenant-Id} is ignored.
 *
 * <p>The realm ships two customers in different tenants — {@code customer-alice}
 * ({@link #ALICE_TENANT}) and {@code customer-bob} ({@link #BOB_TENANT}) — so the
 * cross-tenant assertions use real minted tokens, not stubbed authentication.
 * {@code customers:read}/{@code customers:write} are optional scopes on the
 * {@code originex-web} client, so they are requested explicitly; without them the
 * {@code @PreAuthorize} guards on {@code CustomerUseCase} would 403 and the test
 * would prove nothing about RLS.
 *
 * <p>Kafka and Redis autoconfiguration are excluded: registration writes to the
 * transactional outbox (DB only), so neither is needed and the test stays to one
 * Postgres plus one Keycloak.
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
@DisplayName("Customer HTTP path — RLS tenant isolation driven by verified JWT claims (Testcontainers)")
class CustomerRlsJwtIsolationIntegrationTest {

    /** customer-alice's tenant in infra/keycloak/realm-export.json. */
    private static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";
    /** customer-bob's tenant in infra/keycloak/realm-export.json. */
    private static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String WEB_CLIENT = "originex-web";
    private static final String PASSWORD = "password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_customer");

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
    @DisplayName("a customer registered by alice's tenant is invisible to bob's tenant")
    void jwtClaimScopesReadsToTheCallersTenant() {
        URI location = registerAs(aliceToken, "9990001001");

        ResponseEntity<String> asAlice = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(bearer(aliceToken)), String.class);
        assertThat(asAlice.getStatusCode())
                .as("alice reads back the customer her own tenant created")
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> asBob = rest.exchange(
                location, HttpMethod.GET, new HttpEntity<>(bearer(bobToken)), String.class);
        assertThat(asBob.getStatusCode())
                .as("bob's tenant is blocked by RLS — the row is not visible to originex_app under his tenant")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a spoofed X-Tenant-Id cannot override the verified tenant claim")
    void spoofedTenantHeaderIsIgnored() {
        URI location = registerAs(aliceToken, "9990001002");

        // Bob presents his own token but spoofs alice's tenant in the header. If the
        // header were honoured this would return 200 — the whole point of ENFORCED is
        // that it is ignored, so RLS still scopes him to his own tenant.
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
                "/v1/customers", HttpMethod.POST,
                new HttpEntity<>(body("Nobody", "9990001003"), headers),
                String.class);

        assertThat(resp.getStatusCode())
                .as("no token under ENFORCED → 401, regardless of X-Tenant-Id")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──

    private static String token(String username) {
        // customers:read/customers:write are optional scopes on originex-web and must be
        // requested explicitly, or @PreAuthorize on CustomerUseCase 403s the call.
        return KeycloakSupport.passwordToken(
                KEYCLOAK, WEB_CLIENT, username, PASSWORD, "openid", "customers:read", "customers:write");
    }

    /** Registers a customer as the token's tenant and returns its Location. */
    private URI registerAs(String token, String phone) {
        ResponseEntity<Void> created = rest.exchange(
                "/v1/customers", HttpMethod.POST,
                new HttpEntity<>(body("Alice", phone), bearerJson(token)),
                Void.class);
        assertThat(created.getStatusCode()).as("registration succeeds for an authorized caller")
                .isEqualTo(HttpStatus.CREATED);
        URI location = created.getHeaders().getLocation();
        assertThat(location).as("Location of the created customer").isNotNull();
        return location;
    }

    private static String body(String firstName, String phone) {
        return "{\"firstName\":\"" + firstName + "\",\"lastName\":\"A\",\"phone\":\"" + phone + "\"}";
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
