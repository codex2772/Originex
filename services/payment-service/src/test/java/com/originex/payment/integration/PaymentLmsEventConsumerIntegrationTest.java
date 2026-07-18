package com.originex.payment.integration;

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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Layer-1 seam proof for the lms→payment hop: the real Kafka consumer path.
 *
 * <p>Mirrors {@code LedgerLmsEventConsumerIntegrationTest} for the payment side.
 * {@link com.originex.payment.adapter.in.kafka.LmsPaymentEventConsumer} pulls a real
 * {@code ConsumerRecord<String, byte[]>} off {@code originex.lms.loans.events}, parses
 * the {@code event_id}/{@code event_type}/{@code tenant_id} headers, deserializes the
 * JSON payload (relying on the starter's byte[] value-deserializer default — the serde
 * fix {@code 57bb7c1}), binds the tenant for RLS, and initiates a disbursement — which
 * creates a {@code payment_orders} row and publishes {@code DisbursementInitiated} to
 * the outbox. The event carries the beneficiary details the consumer requires.
 *
 * <p>This proves the forward disbursement leg only. The next hop, {@code payment →
 * DisbursementCompleted → lms} (loan goes ACTIVE), depends on an external bank rail
 * completing and is deliberately NOT asserted here — {@code submitToRail} uses the
 * sandbox fallback and no real settlement is driven.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "management.endpoint.health.validate-group-membership=false"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Payment consumer — LoanDisbursed over Kafka initiates a disbursement, idempotently (lms→payment seam)")
class PaymentLmsEventConsumerIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";
    private static final String TOPIC = "originex.lms.loans.events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_payment");

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
    @DisplayName("a LoanDisbursed event initiates a payment order and publishes DisbursementInitiated")
    void loanDisbursedInitiatesDisbursement() {
        String loanId = UUID.randomUUID().toString();

        publish(UUID.randomUUID().toString(), "originex.lms.LoanDisbursed", loanDisbursedPayload(loanId, "500000"));

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(paymentOrderCount(loanId))
                        .as("the consumer should create exactly one disbursement payment order")
                        .isEqualTo(1));

        assertThat(disbursementInitiatedEventCount(loanId))
                .as("the forward hop's output event must be written to the outbox")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("redelivery of the same event (at-least-once) still yields exactly one payment order")
    void redeliveredEventIsIdempotent() {
        String loanId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        // First delivery — wait until fully processed and committed to the inbox, so the
        // redelivery is the realistic at-least-once repeat of an already-handled event.
        publish(eventId, "originex.lms.LoanDisbursed", loanDisbursedPayload(loanId, "250000"));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(paymentOrderCount(loanId)).isEqualTo(1));

        // Redeliver the identical event (same event_id) — must be dropped by inbox idempotency.
        publish(eventId, "originex.lms.LoanDisbursed", loanDisbursedPayload(loanId, "250000"));

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(paymentOrderCount(loanId))
                        .as("inbox idempotency: a redelivered event_id must not create a second payment order")
                        .isEqualTo(1));
    }

    // ─── helpers ───

    private static String loanDisbursedPayload(String loanId, String amount) {
        // beneficiary_account + beneficiary_ifsc are required by the consumer; the rest optional.
        return String.format(
                "{\"loan_id\":\"%s\",\"customer_id\":\"%s\",\"amount\":\"%s\",\"currency\":\"INR\","
                        + "\"beneficiary_account\":\"1234567890\",\"beneficiary_ifsc\":\"SBIN0001234\","
                        + "\"beneficiary_name\":\"Test Borrower\",\"beneficiary_bank\":\"SBI\"}",
                loanId, UUID.randomUUID(), amount);
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

    private int paymentOrderCount(String loanId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from payment_orders where tenant_id = ?::uuid and loan_id = ?::uuid "
                        + "and payment_type = 'DISBURSEMENT'",
                Integer.class, TENANT, loanId);
        return n == null ? 0 : n;
    }

    private int disbursementInitiatedEventCount(String loanId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from outbox_events o join payment_orders p "
                        + "on o.aggregate_id = p.payment_order_id "
                        + "where p.loan_id = ?::uuid and o.event_type = 'originex.payments.DisbursementInitiated'",
                Integer.class, loanId);
        return n == null ? 0 : n;
    }
}
