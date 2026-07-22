package com.originex.ledger.integration;

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
 * Layer-1 seam proof for the lms→ledger hop: the real Kafka consumer path.
 *
 * <p>{@code LedgerPostingReloadIntegrationTest} proves the posting <i>logic</i> (the #5
 * reload fix) by calling {@code LedgerUseCase} directly. This test proves the parts that
 * one cannot reach: {@link com.originex.ledger.adapter.in.kafka.LmsEventConsumer} pulling
 * a real {@code ConsumerRecord<String, byte[]>} off {@code originex.lms.loans.events},
 * parsing the {@code event_id}/{@code event_type}/{@code tenant_id} headers, deserializing
 * the JSON payload (which relies on the starter's byte[] value-deserializer default — the
 * serde fix {@code 57bb7c1}), binding the tenant via {@code TenantContextHolder} so RLS
 * lets the write through, and — the point of the second test — <b>inbox idempotency</b>.
 *
 * <p>The event shape mirrors exactly what the LMS outbox poller emits: a String key, a
 * JSON {@code byte[]} value, and the three headers. No producer serde shortcut — this is
 * the wire format the real poller puts on the topic.
 *
 * <p>Tenant {@code 00000000-0000-0000-0000-000000000001} is the one seeded with the
 * standard chart of accounts (pool/interest) by {@code V2__seed_chart_of_accounts_and_inbox_table.sql},
 * so the consumer's {@code POOL_ACCOUNT_ID} credit resolves; the loan-receivable account
 * is auto-created on first disbursement. The DB is read as the owner (BYPASSRLS).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "management.endpoint.health.validate-group-membership=false",
                // ENFORCED: @PreAuthorize(ledger:post) is live on postJournalEntry, so this is the
                // decisive test that the Kafka consumer's minimal machine authority (ledger:post)
                // lets auto-posting succeed under enforcement. If the machine auth were missing the
                // post would be denied and journalEntryCount would stay 0. The decoder URI is a
                // placeholder — the consumer path uses a machine SecurityContext, never a JWT.
                "originex.security.enabled=true",
                "originex.security.jwk-set-uri=https://idp.example.com/realms/originex/protocol/openid-connect/certs"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Ledger consumer — LoanDisbursed over Kafka posts a journal entry, idempotently (lms→ledger seam)")
class LedgerLmsEventConsumerIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";
    private static final String TOPIC = "originex.lms.loans.events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_ledger");

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
    /** Owner (BYPASSRLS): the app datasource sees nothing without a bound tenant. */
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
        jdbc = new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
    }

    @AfterAll
    static void tearDown() {
        if (producer != null) producer.close();
    }

    @Test
    @DisplayName("a LoanDisbursed event is consumed and posts a balanced journal entry")
    void loanDisbursedPostsJournalEntry() {
        String loanId = UUID.randomUUID().toString();
        String accountNumber = "LR-" + loanId.substring(0, 8);

        publish(UUID.randomUUID().toString(), "originex.lms.LoanDisbursed", loanDisbursedPayload(loanId, "500000"));

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(journalEntryCount(loanId))
                        .as("the consumer should post exactly one journal entry for this disbursement")
                        .isEqualTo(1));

        assertThat(balanceOfAccount(accountNumber))
                .as("DR loan receivable = disbursed amount (auto-created account starts at zero)")
                .isEqualByComparingTo("500000.00");
    }

    @Test
    @DisplayName("redelivery of the same event (at-least-once) still yields exactly one journal entry")
    void redeliveredEventIsIdempotent() {
        String loanId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        // First delivery — wait until it is fully processed and committed to the inbox, so the
        // redelivery below is the realistic case: an at-least-once repeat of an event whose
        // offset commit lagged, arriving after the first was already handled.
        publish(eventId, "originex.lms.LoanDisbursed", loanDisbursedPayload(loanId, "250000"));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(journalEntryCount(loanId)).isEqualTo(1));

        // Redeliver the identical event (same event_id).
        publish(eventId, "originex.lms.LoanDisbursed", loanDisbursedPayload(loanId, "250000"));

        // It must be dropped by the inbox idempotency check — the count stays at one and never
        // becomes two. `during` proves it *holds*, not merely that it was one at some instant.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(journalEntryCount(loanId))
                        .as("inbox idempotency: a redelivered event_id must not post a second entry")
                        .isEqualTo(1));
    }

    // ─── helpers ───

    private static String loanDisbursedPayload(String loanId, String amount) {
        return String.format(
                "{\"loan_id\":\"%s\",\"amount\":\"%s\",\"currency\":\"INR\"}", loanId, amount);
    }

    private void publish(String eventId, String eventType, String payload) {
        ProducerRecord<String, byte[]> rec =
                new ProducerRecord<>(TOPIC, eventId, payload.getBytes(StandardCharsets.UTF_8));
        rec.headers().add(new RecordHeader("event_id", eventId.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", TENANT.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(rec).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int journalEntryCount(String loanId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ?::uuid and source_id = ?",
                Integer.class, TENANT, loanId);
        return n == null ? 0 : n;
    }

    private BigDecimal balanceOfAccount(String accountNumber) {
        return jdbc.queryForObject(
                "select balance from account_snapshots where tenant_id = ?::uuid and account_number = ?",
                BigDecimal.class, TENANT, accountNumber);
    }
}
