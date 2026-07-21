package com.originex.bre.integration;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bre-service's Phase-2 canary proof. bre is a read-only evaluator, so its
 * isolation shape differs from the create-and-read canaries: {@code POST
 * /v1/bre/evaluate} writes nothing (no outbox, no persisted resource, no GET-back)
 * — it reads the caller's tenant's {@code bre_rule_sets}/{@code bre_rules} (both
 * RLS-protected) and returns a computed decision. The claim proven here is that an
 * evaluation can read <b>only its own tenant's rules</b>.
 *
 * <p>It is still JWT-driven (bre has an HTTP surface, unlike notification), so the
 * tenant comes from the verified claim via {@code TenantClaimResolutionFilter}
 * under {@code ENFORCED}.
 *
 * <p><b>Two independent signals, deliberately separated</b> so a green run can't be
 * explained by anything other than rule visibility:
 * <ul>
 *   <li><b>Datasource (pure RLS):</b> {@code originex_app} scoped to alice's tenant
 *       sees alice's seeded rule set; scoped to bob's tenant sees zero. No rule
 *       semantics involved.</li>
 *   <li><b>HTTP decision contrast:</b> the same request evaluated as alice (who has
 *       a seeded rule set) yields {@code APPROVED}; as bob (no rules, and alice's
 *       hidden by RLS) yields {@code REFER_TO_UNDERWRITER} — the "no rule set
 *       configured" fallback. The contrast is explainable only by which tenant's
 *       rules the evaluation could read.</li>
 * </ul>
 *
 * <p>Rules are seeded for alice's tenant through the <b>owner</b> datasource
 * (BYPASSRLS), mirroring {@code LmsRlsConsumerAndSchedulerIntegrationTest}'s use of
 * the owner to establish cross-tenant fixtures — bre has no write API to create
 * them over HTTP.
 *
 * <p>This test only produces a meaningful contrast because {@code evaluate()} is
 * {@code @Transactional}: the {@code rls} transaction manager sets
 * {@code app.tenant_id} at transaction begin, so a non-transactional read would see
 * no rules for anyone and every decision would be {@code REFER_TO_UNDERWRITER}.
 * That defect was fixed immediately before this test (a silent-wrong-decision bug,
 * not a boot failure).
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
@Tag("keycloak")
@DisplayName("BRE evaluate — RLS tenant isolation over rule reads, driven by verified JWT claims (Testcontainers)")
class BreRlsJwtIsolationIntegrationTest {

    /** customer-alice's tenant in infra/keycloak/realm-export.json — seeded with rules below. */
    private static final String ALICE_TENANT = "11111111-1111-1111-1111-111111111111";
    /** customer-bob's tenant — deliberately left with no rules. */
    private static final String BOB_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String WEB_CLIENT = "originex-web";
    private static final String PASSWORD = "password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_bre");

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
        // Needs only Keycloak (container up), not the schema.
        aliceToken = token("customer-alice");
        bobToken = token("customer-bob");
    }

    @BeforeEach
    void seedRulesOnce() {
        // Seed here, not in @BeforeAll: the schema is created by Flyway during context
        // load, which is guaranteed complete before @BeforeEach but not necessarily
        // before a static @BeforeAll. Idempotent, so it is safe across the test methods.
        seedAliceRuleSetIfAbsent();
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("datasource: the app role sees only its own tenant's rule sets")
    void ruleSetsAreTenantScopedAtTheDatasource() {
        // Pure RLS, no rule semantics: prove the policy hides cross-tenant rows.
        assertThat(visibleRuleSetCountAsApp(ALICE_TENANT))
                .as("originex_app under alice's tenant sees the seeded rule set")
                .isEqualTo(1);
        assertThat(visibleRuleSetCountAsApp(BOB_TENANT))
                .as("originex_app under bob's tenant sees none — alice's is hidden by RLS")
                .isZero();
    }

    @Test
    @DisplayName("evaluation reads only the caller's tenant's rules: alice APPROVED, bob REFER")
    void evaluationReadsOnlyTheCallersTenantRules() {
        // alice has a rule set (seeded) and a strong applicant -> APPROVED.
        assertThat(decisionAs(aliceToken, null))
                .as("alice's evaluation reads alice's rules under her claim's tenant")
                .isEqualTo("APPROVED");

        // bob has no rules and cannot see alice's -> the no-rule-set fallback.
        assertThat(decisionAs(bobToken, null))
                .as("bob cannot read alice's rules (RLS) and has none of his own")
                .isEqualTo("REFER_TO_UNDERWRITER");
    }

    @Test
    @DisplayName("a spoofed X-Tenant-Id cannot smuggle access to another tenant's rules")
    void spoofedTenantHeaderIsIgnored() {
        // bob spoofs alice's tenant in the header. If the header were honoured he
        // would read alice's rules and get APPROVED; under ENFORCED the claim wins,
        // so he stays scoped to his own (empty) tenant and still gets REFER.
        assertThat(decisionAs(bobToken, ALICE_TENANT))
                .as("header is ignored under ENFORCED; bob's own claim decides, alice's rules stay hidden")
                .isEqualTo("REFER_TO_UNDERWRITER");

        // The other direction: a misleading header does not lock alice out of her own rules.
        assertThat(decisionAs(aliceToken, BOB_TENANT))
                .as("alice still reads her own rules; her claim, not the header, decides")
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", ALICE_TENANT);

        ResponseEntity<String> resp = rest.exchange(
                "/v1/bre/evaluate", HttpMethod.POST, new HttpEntity<>(strongApplicant(), headers), String.class);

        assertThat(resp.getStatusCode())
                .as("no token under ENFORCED → 401, regardless of X-Tenant-Id")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ──

    private static String token(String username) {
        // No scopes requested: bre has no @PreAuthorize, and tenant_id rides on the
        // originex-tenant default client scope.
        return KeycloakSupport.passwordToken(KEYCLOAK, WEB_CLIENT, username, PASSWORD, "openid");
    }

    /** POSTs the strong applicant; {@code spoofedTenant} may be null. Returns the decision string. */
    private String decisionAs(String token, String spoofedTenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (spoofedTenant != null) {
            headers.set("X-Tenant-Id", spoofedTenant);
        }
        ResponseEntity<EvaluationDecision> resp = rest.exchange(
                "/v1/bre/evaluate", HttpMethod.POST, new HttpEntity<>(strongApplicant(), headers),
                EvaluationDecision.class);
        assertThat(resp.getStatusCode()).as("evaluate returns 200 for an authenticated caller")
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody().decision();
    }

    /** An applicant that passes every HARD and SOFT rule in the seeded set → APPROVED. */
    private static String strongApplicant() {
        return """
                {"applicationId":"aaaaaaaa-0000-0000-0000-000000000001",\
                "customerId":"cccccccc-0000-0000-0000-000000000001",\
                "productCode":"PERSONAL_LOAN","employmentType":"SALARIED","creditScore":750,\
                "bureauName":"CIBIL","hasWriteOff":false,"hasSettlement":false,\
                "enquiriesLast6Months":1,"activeLoansCount":1,"existingEmiObligations":"10000",\
                "monthlyIncome":"100000","applicantAgeYears":30,"requestedAmount":"500000",\
                "requestedTenureMonths":24,"currency":"INR"}""";
    }

    /**
     * Seeds a DEFAULT rule set + its hard/soft rules for alice's tenant, as the owner
     * (BYPASSRLS), unless already present. bob's tenant is left empty. The rules mirror
     * the platform's own V1 DEFAULT set so a strong applicant deterministically yields
     * APPROVED. Idempotent — safe to call before each test.
     */
    private static void seedAliceRuleSetIfAbsent() {
        JdbcTemplate owner = new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
        Integer existing = owner.queryForObject(
                "select count(*) from bre_rule_sets where tenant_id = ?::uuid", Integer.class, ALICE_TENANT);
        if (existing != null && existing > 0) {
            return;
        }
        String ruleSetId = "c0000000-0000-0000-0000-0000000000a1";
        owner.update("""
                insert into bre_rule_sets (rule_set_id, tenant_id, rule_set_code, product_code, employment_type, description)
                values (?::uuid, ?::uuid, 'DEFAULT', null, null, 'canary default rule set')
                """, ruleSetId, ALICE_TENANT);
        insertRule(owner, ruleSetId, "MIN_CREDIT_SCORE", "HARD", "CREDIT", "credit_score", "GTE", "600", 10);
        insertRule(owner, ruleSetId, "NO_WRITE_OFF", "HARD", "DELINQUENCY", "has_write_off", "EQ", "false", 20);
        insertRule(owner, ruleSetId, "MIN_AGE", "HARD", "AGE", "applicant_age", "GTE", "21", 30);
        insertRule(owner, ruleSetId, "MIN_INCOME", "HARD", "INCOME", "monthly_income", "GTE", "15000", 50);
        insertRule(owner, ruleSetId, "MAX_FOIR", "SOFT", "INCOME", "foir", "LTE", "0.50", 60);
    }

    private static void insertRule(JdbcTemplate owner, String ruleSetId, String code, String type,
                                   String category, String factKey, String operator, String threshold, int priority) {
        owner.update("""
                insert into bre_rules (rule_id, tenant_id, rule_set_id, rule_code, description, rule_type,
                                       category, fact_key, operator, threshold_value, failure_message, priority)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ALICE_TENANT, ruleSetId, code, code, type, category, factKey, operator, threshold, code, priority);
    }

    /** Distinct-tenant count of rule sets visible to originex_app with app.tenant_id set. */
    private static int visibleRuleSetCountAsApp(String tenantId) {
        JdbcTemplate app = new JdbcTemplate(RlsPostgresSupport.appDataSource(POSTGRES));
        return app.execute((java.sql.Connection c) -> {
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("set app.tenant_id = '" + tenantId + "'");
                try (java.sql.ResultSet rs = s.executeQuery("select count(*) from bre_rule_sets")) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /** Minimal view of EvaluationResponse — only the decision this test asserts on. */
    private record EvaluationDecision(String decision) {
    }
}
