package com.originex.partner.integration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * partner-integration's Phase-2 canary proof, and its first test of any kind
 * (the service had zero). It is the only <b>write-side</b> canary: a verify
 * request persists an {@code integration_requests} row (RLS-protected) under the
 * caller's tenant, and there is no GET-back endpoint — so isolation is asserted at
 * the datasource, and the write itself proves RLS {@code WITH CHECK}.
 *
 * <p>JWT-driven (the service has an HTTP surface): the tenant comes from the
 * verified claim via {@code TenantClaimResolutionFilter} under {@code ENFORCED}.
 * The sandbox partner adapters make no external calls, so no collaborator needs
 * mocking (unlike los).
 *
 * <p><b>What is proven</b>, all confirmed first by driving the same path manually
 * against real Postgres + Keycloak:
 * <ul>
 *   <li><b>Datasource isolation:</b> a row written by alice's verify is visible to
 *       {@code originex_app} scoped to alice's tenant and invisible to it scoped to
 *       bob's — the pure RLS claim.</li>
 *   <li><b>Write-side WITH CHECK / claim wins:</b> bob verifying with a spoofed
 *       {@code X-Tenant-Id} pointing at alice's tenant still persists the row under
 *       <b>bob's</b> tenant — the header cannot smuggle a write into another tenant.</li>
 *   <li><b>ENFORCED:</b> an unauthenticated request is 401.</li>
 * </ul>
 *
 * <p>No cache-leak assertion: only the credit-bureau path caches
 * ({@code findLatestValidCache}), and PAN verify — used here — does not, so "one
 * row per tenant" would prove nothing about caching. That was confirmed by a
 * same-tenant baseline during the boot-drive (a repeat PAN verify writes a second
 * row), and the assertion was dropped rather than kept as a weaker substitute.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "management.endpoint.health.validate-group-membership=false"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Partner-integration verify — write-side RLS isolation driven by verified JWT claims (Testcontainers)")
class PartnerRlsJwtIsolationIntegrationTest {

    private static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String WEB_CLIENT = "originex-web";
    private static final String PASSWORD = "password";
    /** Valid PAN format ([A-Z]{5}[0-9]{4}[A-Z]); 4th char P → INDIVIDUAL → sandbox success. */
    private static final String VALID_PAN = "ABCPA1234A";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_partner");

    @Container
    static final GenericContainer<?> KEYCLOAK = KeycloakSupport.newContainer();

    private static String aliceToken;
    private static String bobToken;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
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
    @DisplayName("a row written by alice's verify is visible only to alice's tenant, hidden from bob's")
    void writesAreTenantScopedAtTheDatasource() {
        String ref = "IT-ALICE-1";
        verify(aliceToken, ref, null);

        // Owner (BYPASSRLS) confirms the row exists and carries alice's tenant.
        assertThat(tenantOfRow(ref))
                .as("the verify persisted under the tenant from alice's JWT claim")
                .isEqualTo(ALICE_TENANT);

        // The RLS claim: app under alice's tenant sees the row; under bob's it does not.
        assertThat(rowVisibleToApp(ref, ALICE_TENANT))
                .as("originex_app under alice's tenant sees alice's row").isTrue();
        assertThat(rowVisibleToApp(ref, BOB_TENANT))
                .as("originex_app under bob's tenant cannot see alice's row — RLS hides it").isFalse();
    }

    @Test
    @DisplayName("a spoofed X-Tenant-Id cannot smuggle a write into another tenant (WITH CHECK)")
    void spoofedTenantHeaderCannotMisdirectTheWrite() {
        String ref = "IT-SPOOF-1";
        // bob verifies but spoofs alice's tenant in the header. If the header were
        // honoured the row would land under alice's tenant; under ENFORCED the claim
        // wins, so it lands under bob's — and RLS WITH CHECK would have rejected a
        // write stamped with a tenant other than the session's anyway.
        verify(bobToken, ref, ALICE_TENANT);

        assertThat(tenantOfRow(ref))
                .as("the row is stamped with bob's own tenant, not the spoofed alice tenant")
                .isEqualTo(BOB_TENANT);
        assertThat(rowVisibleToApp(ref, ALICE_TENANT))
                .as("alice's tenant cannot see bob's row, spoof notwithstanding").isFalse();
        assertThat(rowVisibleToApp(ref, BOB_TENANT))
                .as("bob's tenant sees his own row").isTrue();
    }

    @Test
    @DisplayName("an unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", ALICE_TENANT);

        ResponseEntity<String> resp = rest.exchange(
                "/v1/partner/pan/verify", HttpMethod.POST,
                new HttpEntity<>(body("IT-NOAUTH-1"), headers), String.class);

        assertThat(resp.getStatusCode())
                .as("no token under ENFORCED → 401, regardless of X-Tenant-Id")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──

    private static String token(String username) {
        // No scopes requested: the service has no @PreAuthorize, and tenant_id rides
        // on the originex-tenant default client scope.
        return KeycloakSupport.passwordToken(KEYCLOAK, WEB_CLIENT, username, PASSWORD, "openid");
    }

    /** POSTs a PAN verify as the token's tenant; {@code spoofedTenant} may be null. */
    private void verify(String token, String referenceId, String spoofedTenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (spoofedTenant != null) {
            headers.set("X-Tenant-Id", spoofedTenant);
        }
        ResponseEntity<String> resp = rest.exchange(
                "/v1/partner/pan/verify", HttpMethod.POST, new HttpEntity<>(body(referenceId), headers), String.class);
        assertThat(resp.getStatusCode()).as("verify succeeds for an authenticated caller")
                .isEqualTo(HttpStatus.OK);
    }

    private static String body(String referenceId) {
        return """
                {"referenceId":"%s","panNumber":"%s","fullName":"Alice A"}"""
                .formatted(referenceId, VALID_PAN);
    }

    /** Tenant stamped on the row, read as owner (BYPASSRLS) — is the write in the right place? */
    private static String tenantOfRow(String referenceId) {
        JdbcTemplate owner = new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
        return owner.queryForObject(
                "select tenant_id::text from integration_requests where reference_id = ?", String.class, referenceId);
    }

    /** Whether originex_app, scoped to {@code tenantId}, can see the row — the RLS claim. */
    private static boolean rowVisibleToApp(String referenceId, String tenantId) {
        JdbcTemplate app = new JdbcTemplate(RlsPostgresSupport.appDataSource(POSTGRES));
        Integer n = app.execute((java.sql.Connection c) -> {
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("set app.tenant_id = '" + tenantId + "'");
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                        "select count(*) from integration_requests where reference_id = ?")) {
                    ps.setString(1, referenceId);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            }
        });
        return n != null && n > 0;
    }
}
