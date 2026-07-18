package com.originex.lms.integration;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.application.service.InterestAccrualService;
import com.originex.testsupport.rls.RlsPostgresSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end verification of the LMS core lifecycle against real Postgres + Kafka
 * (Testcontainers) — the first integration test in the project. Drives the slice
 * that the recent disbursement + interest-accrual commits wired: a
 * DisbursementRequested event through loan creation, disbursement initiation,
 * activation, interest accrual, and repayment reachability — asserting REST-free
 * side effects in the DB, the transactional outbox, and the inbox.
 *
 * <p>Run locally with: {@code mvn -s dev/settings.xml -pl services/lms-service -am
 * verify -Pintegration-test}. Runs in CI via {@code mvn verify -Pintegration-test}.
 *
 * <p><b>Runs under the {@code rls} profile</b> (the service is enabled under RLS), so
 * the app connects as {@code originex_app} (NOBYPASSRLS). That changes how the test
 * touches the database:
 * <ul>
 *   <li>The consumer path is unaffected — events carry the {@code tenant_id} header and
 *       {@code TenantRecordInterceptor} sets the tenant before each consumer transaction
 *       (wiring proven by {@code LmsRlsConsumerAndSchedulerIntegrationTest}).</li>
 *   <li>The test's own reads and setup writes go through an <b>owner</b> (BYPASSRLS)
 *       {@link JdbcTemplate}, not the app datasource: this is a lifecycle-<i>mechanics</i>
 *       test that must observe and manipulate rows across the flow regardless of tenant
 *       context. Isolation itself is the sibling test's job.</li>
 *   <li>The scheduler calls route to the system (BYPASSRLS) role internally, unchanged.</li>
 *   <li>The one direct app-path call, {@code recordRepayment}, is wrapped in
 *       {@link TenantContextHolder} so the RLS transaction manager sets {@code app.tenant_id}
 *       — exactly what the HTTP filter / Kafka interceptor do in production. Verified by
 *       observation before this migration.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Never let the daily accrual cron fire mid-test; we invoke it directly.
                "originex.lms.accrual.cron=0 0 3 1 1 ?",
                // No Redis in the test harness; LMS declares it but never uses it.
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                // Redis is excluded and actuator probes are inactive off-Kubernetes, so the
                // app's health groups reference contributors absent here (livenessState, redis).
                // Don't fail context load validating group membership.
                "management.endpoint.health.validate-group-membership=false"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("LMS loan lifecycle — disbursement → active → accrual → repayment, under RLS (Testcontainers)")
class LoanLifecycleIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_lms");

    @Container
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static KafkaProducer<String, byte[]> producer;

    /**
     * The test's own DB access runs as the owner (BYPASSRLS) — see the class javadoc.
     * Not {@code @Autowired}: under the rls profile the app's routing datasource
     * connects as {@code originex_app}, which would see nothing without a tenant
     * context bound on this thread.
     */
    private static JdbcTemplate jdbc;

    @Autowired LoanUseCase loanUseCase;
    @Autowired InterestAccrualService accrual;
    @Autowired com.originex.lms.application.service.DpdAgingService dpdAging;

    @BeforeAll
    static void startProducer() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
        jdbc = new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) producer.close();
    }

    @Test
    void disbursementThroughRepaymentReachability() {
        UUID customerId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();

        // ── 1. DisbursementRequested → loan created + disbursement initiated + LoanDisbursed ──
        String reqEventId = UUID.randomUUID().toString();
        String reqPayload = """
                {"application_id":"%s","customer_id":"%s","product_code":"PERSONAL_LOAN",
                 "sanctioned_amount":"500000","interest_rate":"12.5","rate_type":"FIXED",
                 "tenure_months":24,"emi":"23536.74","currency":"INR",
                 "beneficiary_account":"1234567890","beneficiary_ifsc":"SBIN0001234",
                 "beneficiary_name":"Test Borrower","beneficiary_bank":"SBI"}""".formatted(applicationId, customerId);
        publish("originex.los.applications.events", reqEventId, "originex.los.DisbursementRequested", reqPayload);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(statusByApplication(applicationId)).isEqualTo("PENDING_DISBURSAL"));

        UUID loanId = loanIdByApplication(applicationId);
        // an INITIATED disbursement exists (so confirmDisbursementByPayment can confirm it)
        assertThat(jdbc.queryForObject(
                "select status from disbursements where tenant_id = ?::uuid and loan_id = ?", String.class, TENANT, loanId))
                .isEqualTo("INITIATED");
        // LoanDisbursed is in the outbox, carrying the beneficiary; inbox recorded the request
        String loanDisbursedPayload = outboxPayload("originex.lms.LoanDisbursed");
        assertThat(loanDisbursedPayload).contains("\"beneficiary_account\":\"1234567890\"");
        assertThat(inboxCount(reqEventId)).isEqualTo(1);

        // ── 2. Idempotency: redeliver DisbursementRequested → no second loan ──
        publish("originex.los.applications.events", reqEventId, "originex.los.DisbursementRequested", reqPayload);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select count(*) from loans where application_id = ?", Integer.class, applicationId))
                        .isEqualTo(1));

        // ── 3. DisbursementCompleted → loan ACTIVE, accrual baseline set ──
        String compPayload = """
                {"loan_id":"%s","payment_order_id":"%s","utr":"UTR-TEST-1"}"""
                .formatted(loanId, UUID.randomUUID());
        publish("originex.payments.orders.events", UUID.randomUUID().toString(),
                "originex.payments.DisbursementCompleted", compPayload);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(statusById(loanId)).isEqualTo("ACTIVE"));
        assertThat(jdbc.queryForObject("select last_accrual_date from loans where loan_id = ?",
                java.time.LocalDate.class, loanId)).isNotNull();

        // ── 4. Interest accrual (time-shift the baseline back so days elapse) ──
        jdbc.update("update loans set last_accrual_date = last_accrual_date - interval '5 day' where loan_id = ?", loanId);
        accrual.runDailyAccrual();
        BigDecimal afterFirst = outstandingInterest(loanId);
        assertThat(afterFirst).isGreaterThan(BigDecimal.ZERO);
        assertThat(outboxPayload("originex.lms.InterestAccrued")).contains(loanId.toString());

        // idempotent: a same-day rerun accrues nothing further
        accrual.runDailyAccrual();
        assertThat(outstandingInterest(loanId)).isEqualByComparingTo(afterFirst);

        // ── 5. Repayment reachable on an ACTIVE loan (would have thrown 'not active' on CREATED) ──
        // Direct app-path call: bind the tenant so the RLS transaction manager sets
        // app.tenant_id (the HTTP filter / Kafka interceptor do this in production).
        TenantContextHolder.set(TenantContext.of(TENANT, TENANT));
        try {
            loanUseCase.recordRepayment(new LoanUseCase.RecordRepaymentCommand(
                    UUID.fromString(TENANT), loanId, "10000", "INR", "PAY-REPAY-1"));
        } finally {
            TenantContextHolder.clear();
        }
        assertThat(outboxPayload("originex.lms.RepaymentAllocated")).contains(loanId.toString());
        // repayment settled the schedule: the oldest installment now shows paid amounts
        assertThat(jdbc.queryForObject(
                "select count(*) from installments where loan_id = ? and (principal_paid > 0 or interest_paid > 0)",
                Integer.class, loanId)).isGreaterThanOrEqualTo(1);

        // ── 6. DPD / NPA aging: back-date the due date past 90 days, run the job → NPA ──
        jdbc.update("update loans set next_due_date = ? where loan_id = ?",
                java.time.LocalDate.now().minusDays(95), loanId);
        dpdAging.runDailyAging();
        assertThat(statusById(loanId)).isEqualTo("NPA");
        assertThat(jdbc.queryForObject("select asset_classification from loans where loan_id = ?",
                String.class, loanId)).isEqualTo("SUB_STANDARD");
        assertThat(jdbc.queryForObject("select dpd from loans where loan_id = ?",
                Integer.class, loanId)).isGreaterThanOrEqualTo(90);
    }

    // ── helpers ──

    private void publish(String topic, String eventId, String eventType, String payload) {
        ProducerRecord<String, byte[]> rec =
                new ProducerRecord<>(topic, eventId, payload.getBytes(StandardCharsets.UTF_8));
        rec.headers().add(new RecordHeader("event_id", eventId.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", TENANT.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(rec).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String statusByApplication(UUID applicationId) {
        var rows = jdbc.queryForList("select status from loans where application_id = ?", String.class, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID loanIdByApplication(UUID applicationId) {
        return jdbc.queryForObject("select loan_id from loans where application_id = ?", UUID.class, applicationId);
    }

    private String statusById(UUID loanId) {
        return jdbc.queryForObject("select status from loans where loan_id = ?", String.class, loanId);
    }

    private BigDecimal outstandingInterest(UUID loanId) {
        return jdbc.queryForObject("select outstanding_interest from loans where loan_id = ?", BigDecimal.class, loanId);
    }

    private String outboxPayload(String eventType) {
        var rows = jdbc.queryForList(
                "select convert_from(payload, 'UTF8') from outbox_events where event_type = ? order by created_at desc",
                String.class, eventType);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    private int inboxCount(String eventId) {
        return jdbc.queryForObject("select count(*) from inbox_events where event_id = ?::uuid", Integer.class, eventId);
    }
}
