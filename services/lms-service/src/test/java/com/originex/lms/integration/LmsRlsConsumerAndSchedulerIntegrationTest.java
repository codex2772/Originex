package com.originex.lms.integration;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * App-level (Layer 2) proof of the two RLS wiring paths unique to LMS, with the
 * service running as {@code originex_app} under {@code @ActiveProfiles("rls")}
 * (Flyway as {@code originex_owner} via the shared harness):
 *
 * <ul>
 *   <li><b>Kafka consumer path</b> — {@code TenantRecordInterceptor} sets tenant
 *       context from the {@code tenant_id} header before the consumer transaction,
 *       so a {@code DisbursementRequested} event for tenant A creates a loan that
 *       only tenant A can see. (Without the interceptor the insert would violate
 *       WITH CHECK and no loan would appear.)</li>
 *   <li><b>Scheduler path</b> — {@code InterestAccrualService.runDailyAccrual()}
 *       runs inside {@code SystemContextHolder.runAsSystem(...)}, so its scan is
 *       routed to the BYPASSRLS system role and processes loans across <em>all</em>
 *       tenants. If the system-context wrapping regressed, the app-route scan would
 *       be fail-closed and neither tenant's loan would be touched.</li>
 * </ul>
 *
 * <p>Isolation is asserted through direct per-role datasources from the harness;
 * loans are created via the real Kafka flow and driven to an accrual-eligible
 * state through the owner (BYPASSRLS) datasource. Requires Docker (Testcontainers).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Freeze both daily jobs; the test invokes accrual directly.
                "originex.lms.accrual.cron=0 0 3 1 1 ?",
                "originex.lms.dpd.cron=0 0 3 1 1 ?",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("LMS RLS wiring — Kafka consumer isolation + cross-tenant scheduler (Testcontainers)")
class LmsRlsConsumerAndSchedulerIntegrationTest {

    private static final String TENANT_A = "00000000-0000-0000-0000-00000000000a";
    private static final String TENANT_B = "00000000-0000-0000-0000-00000000000b";

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
    private static DataSource appDs;
    private static DataSource ownerDs;

    @Autowired
    InterestAccrualService accrual;

    @BeforeAll
    static void setUp() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
        appDs = RlsPostgresSupport.appDataSource(POSTGRES);
        ownerDs = RlsPostgresSupport.ownerDataSource(POSTGRES);
    }

    @AfterAll
    static void tearDown() {
        if (producer != null) producer.close();
    }

    @Test
    @DisplayName("consumer writes are tenant-scoped and the accrual scheduler spans all tenants")
    void consumerIsolationAndCrossTenantScheduler() throws SQLException {
        UUID appA = UUID.randomUUID();
        UUID appB = UUID.randomUUID();

        // ── Kafka consumer path: create one loan per tenant via DisbursementRequested ──
        publishDisbursementRequested(TENANT_A, appA);
        publishDisbursementRequested(TENANT_B, appB);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(loanCount(ownerDs, appA)).isEqualTo(1);
            assertThat(loanCount(ownerDs, appB)).isEqualTo(1);
        });

        // Isolation: each tenant sees only its own loan through the app (RLS) role.
        assertThat(visibleAsTenant(TENANT_A, appA)).as("A sees its loan").isTrue();
        assertThat(visibleAsTenant(TENANT_A, appB)).as("A cannot see B's loan").isFalse();
        assertThat(visibleAsTenant(TENANT_B, appB)).as("B sees its loan").isTrue();
        assertThat(visibleAsTenant(TENANT_B, appA)).as("B cannot see A's loan").isFalse();

        // ── Scheduler path: make both loans accrual-eligible, then run the job ──
        LocalDate beforeA = forceAccrualEligible(appA);
        LocalDate beforeB = forceAccrualEligible(appB);

        accrual.runDailyAccrual();

        // The processor advances last_accrual_date for every loan it processes; both
        // advancing proves the system-context scan saw both tenants (not just one).
        assertThat(lastAccrualDate(appA)).as("tenant A loan accrued").isAfter(beforeA);
        assertThat(lastAccrualDate(appB)).as("tenant B loan accrued").isAfter(beforeB);
    }

    // ── Kafka ──

    private void publishDisbursementRequested(String tenant, UUID applicationId) {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"application_id":"%s","customer_id":"%s","product_code":"PERSONAL_LOAN",
                 "sanctioned_amount":"500000","interest_rate":"12.5","rate_type":"FIXED",
                 "tenure_months":24,"emi":"23536.74","currency":"INR",
                 "beneficiary_account":"1234567890","beneficiary_ifsc":"SBIN0001234",
                 "beneficiary_name":"Test Borrower","beneficiary_bank":"SBI"}"""
                .formatted(applicationId, UUID.randomUUID());
        ProducerRecord<String, byte[]> rec = new ProducerRecord<>(
                "originex.los.applications.events", eventId, payload.getBytes(StandardCharsets.UTF_8));
        rec.headers().add(new RecordHeader("event_id", eventId.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event_type",
                "originex.los.DisbursementRequested".getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(rec).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── DB helpers (per-role datasources from the harness) ──

    private static int loanCount(DataSource ds, UUID applicationId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from loans where application_id = ?::uuid")) {
            ps.setString(1, applicationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean visibleAsTenant(String tenant, UUID applicationId) throws SQLException {
        try (Connection c = appDs.getConnection()) {
            try (PreparedStatement set = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                set.setString(1, tenant);
                set.executeQuery();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "select count(*) from loans where application_id = ?::uuid")) {
                ps.setString(1, applicationId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            }
        }
    }

    /** Drives a loan to an accrual-eligible state (owner role, BYPASSRLS) and returns its backdated baseline. */
    private static LocalDate forceAccrualEligible(UUID applicationId) throws SQLException {
        try (Connection c = ownerDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "update loans set status = 'ACTIVE', outstanding_principal = 500000, "
                             + "last_accrual_date = current_date - interval '5 day' "
                             + "where application_id = ?::uuid")) {
            ps.setString(1, applicationId.toString());
            ps.executeUpdate();
        }
        return lastAccrualDate(applicationId);
    }

    private static LocalDate lastAccrualDate(UUID applicationId) throws SQLException {
        try (Connection c = ownerDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select last_accrual_date from loans where application_id = ?::uuid")) {
            ps.setString(1, applicationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDate.class);
            }
        }
    }
}
