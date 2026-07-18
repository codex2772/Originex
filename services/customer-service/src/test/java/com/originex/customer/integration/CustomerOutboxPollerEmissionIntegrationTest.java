package com.originex.customer.integration;

import com.originex.testsupport.rls.RlsPostgresSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Producer-emission proof: the <b>real</b> {@link com.originex.starter.outbox.OutboxPoller}
 * publishes to the correct topic, with the required headers, in {@code byte[]} serde.
 *
 * <p>This is the one thing the consumer-side seam ITs structurally cannot check: they inject
 * events with a <i>test</i> producer, so nothing exercises a real service's poller emitting to
 * Kafka. The poller is shared starter code, so exercising it here (via customer, the cheapest
 * producer to trigger — one HTTP POST) guards the emission contract for every service.
 *
 * <p>It fails on all three residual failure classes a seam IT would miss:
 * <ul>
 *   <li><b>serde</b> — the poller sends a {@code byte[]} payload; if the value-serializer
 *       regressed to {@code StringSerializer} (the original serde gap's producer half), the send
 *       throws, the event stays PENDING, and no record ever arrives — the await below fails;</li>
 *   <li><b>topic routing</b> — {@code resolveTopicFromEventType} maps
 *       {@code originex.customer.*} to {@code originex.customer.customers.events}; the consumer
 *       subscribes to exactly that topic, so a routing regression yields no record;</li>
 *   <li><b>header emission</b> — consumers key off {@code event_id}/{@code event_type}/{@code
 *       tenant_id} headers; a poller that stopped emitting one is asserted here, not just assumed.</li>
 * </ul>
 *
 * <p>Registration is DB-only synchronously; the {@code @Scheduled} poller publishes asynchronously,
 * which is why the record is awaited. Security is off (the {@code rls} profile with no issuer), so
 * {@code TenantResolutionFilter} binds the tenant from {@code X-Tenant-Id} — no Keycloak container.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Kafka is deliberately NOT excluded here (unlike the RLS-isolation IT) — the real
                // poller publishing to a real broker is the whole point.
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "management.endpoint.health.validate-group-membership=false"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Customer outbox poller — emits CustomerRegistered to the right topic, headers, byte[] serde")
class CustomerOutboxPollerEmissionIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";
    /** The topic OutboxPoller.resolveTopicFromEventType maps originex.customer.* to. */
    private static final String EXPECTED_TOPIC = "originex.customer.customers.events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_customer");

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

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("the real poller publishes to originex.customer.customers.events with headers and byte[] value")
    void pollerEmitsCorrectTopicHeadersAndSerde() {
        try (KafkaConsumer<String, byte[]> consumer = testConsumer()) {
            consumer.subscribe(List.of(EXPECTED_TOPIC));
            consumer.poll(Duration.ofMillis(500)); // trigger partition assignment before we publish

            // Register a customer (no PAN → no external verification): writes CustomerRegistered to
            // the outbox; the real poller then publishes it to Kafka.
            ResponseEntity<Void> created = rest.exchange(
                    "/v1/customers", HttpMethod.POST,
                    new HttpEntity<>(
                            "{\"firstName\":\"Poller\",\"lastName\":\"Emit\",\"phone\":\"9990001234\"}",
                            jsonHeaders(TENANT)),
                    Void.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ConsumerRecord<String, byte[]> rec = awaitCustomerRegistered(consumer, Duration.ofSeconds(40));

            // topic routing — it arrived on the topic we subscribed to (a routing regression yields none)
            assertThat(rec.topic()).isEqualTo(EXPECTED_TOPIC);

            // header emission — consumers key off these
            assertThat(header(rec, "event_type")).isEqualTo("originex.customer.CustomerRegistered");
            assertThat(header(rec, "tenant_id")).isEqualTo(TENANT);
            assertThat(header(rec, "event_id")).as("event_id header present").isNotBlank();
            assertThat(header(rec, "aggregate_id")).as("aggregate_id header present").isNotBlank();

            // serde — the value is the byte[] JSON payload. A StringSerializer regression would have
            // thrown at publish time (byte[] is unserializable by it), so no record would arrive at all.
            assertThat(rec.value()).as("value present as raw bytes").isNotEmpty();
            String payload = new String(rec.value(), StandardCharsets.UTF_8);
            assertThat(payload)
                    .as("byte[] value decodes to the CustomerRegistered JSON payload")
                    .startsWith("{")
                    .contains("\"customer_id\":")
                    .contains("\"first_name\":\"Poller\"");
        }
    }

    // ─── helpers ───

    private KafkaConsumer<String, byte[]> testConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "emission-probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName()));
    }

    private ConsumerRecord<String, byte[]> awaitCustomerRegistered(
            KafkaConsumer<String, byte[]> consumer, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> rec : records) {
                if ("originex.customer.CustomerRegistered".equals(header(rec, "event_type"))) {
                    return rec;
                }
            }
        }
        return fail("No CustomerRegistered record published to %s within %s — "
                + "the real poller did not emit it (serde/topic/publish regression)", EXPECTED_TOPIC, timeout);
    }

    private static String header(ConsumerRecord<String, byte[]> rec, String key) {
        Header h = rec.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static HttpHeaders jsonHeaders(String tenantId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Tenant-Id", tenantId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
