package com.originex.ledger.kafkaobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.originex.starter.kafka.KafkaEventEnvelope;
import com.originex.starter.kafka.OriginexKafkaErrorHandlingAutoConfiguration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STAGE 2 REGRESSION TEST — asserts the shipped shared error-handling policy end-to-end against a
 * real embedded Kafka broker, using the actual {@link OriginexKafkaErrorHandlingAutoConfiguration}
 * bean applied to the default {@code kafkaListenerContainerFactory} (exactly how every service gets
 * it). This is the forward guard grown from the Step 0 observation harness.
 *
 * Asserts:
 *  1. Poison (PoisonEventException / bad JSON) → routed to DLQ on the FIRST attempt (no wasted retries).
 *  2. Transient (retryable RuntimeException)   → 4 attempts (1 + 3 retries) then routed to DLQ.
 *  3. The original {@code event_id} header is preserved on the DLQ record (replay/dedupe depends on it).
 *  4. A good record is processed normally and never reaches the DLQ.
 */
@SpringBootTest(
        classes = KafkaErrorHandlingRegressionTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.group-id=regression-consumer",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer"
        }
)
@EmbeddedKafka(partitions = 1, topics = {"originex.test.events", "originex.test.events.dlq"})
class KafkaErrorHandlingRegressionTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingRegressionTest.class);
    private static final String TOPIC = "originex.test.events";
    private static final String DLQ = "originex.test.events.dlq";

    @Autowired
    KafkaTemplate<String, byte[]> template;
    @Autowired
    TestListener listener;
    @Autowired
    EmbeddedKafkaBroker broker;

    @Test
    void poisonAndTransientAreRoutedToDlqWithHeadersPreserved() {
        String poisonEventId = "11111111-1111-1111-1111-111111111111";
        String transientEventId = "22222222-2222-2222-2222-222222222222";
        String goodEventId = "33333333-3333-3333-3333-333333333333";

        send("poison", poisonEventId, "{ not valid json ]");        // bad JSON -> PoisonEventException
        send("transient", transientEventId, "{\"mode\":\"transient\"}"); // valid JSON, listener throws retryable
        send("good", goodEventId, "{\"mode\":\"good\"}");            // valid JSON, succeeds
        template.flush();

        // Drain the DLQ until both expected records arrive (or timeout). Transient path takes ~7s.
        Map<String, ConsumerRecord<String, byte[]>> dlq = drainDlq(2, Duration.ofSeconds(25));

        log.warn("=========== STAGE 2 REGRESSION RESULTS ===========");
        log.warn("poison    deliveries = {} (expect 1: non-retryable)", count("poison"));
        log.warn("transient deliveries = {} (expect 4: 1 + 3 retries)", count("transient"));
        log.warn("good      deliveries = {} (expect 1: processed)", count("good"));
        log.warn("good processed flag  = {}", listener.goodProcessed.get());
        log.warn("DLQ keys received    = {}", dlq.keySet());
        log.warn("==================================================");

        // 1. poison -> DLQ on first attempt
        assertThat(count("poison")).as("poison delivered exactly once (non-retryable)").isEqualTo(1);
        assertThat(dlq).as("poison routed to DLQ").containsKey("poison");

        // 2. transient -> 4 attempts then DLQ
        assertThat(count("transient")).as("transient delivered 1 + 3 retries").isEqualTo(4);
        assertThat(dlq).as("transient routed to DLQ").containsKey("transient");

        // 3. event_id header preserved on the DLQ record
        assertThat(headerValue(dlq.get("poison"), "event_id"))
                .as("event_id preserved on DLQ record").isEqualTo(poisonEventId);

        // 4. good processed, never in DLQ
        assertThat(listener.goodProcessed.get()).as("good record processed").isTrue();
        assertThat(dlq).as("good never routed to DLQ").doesNotContainKey("good");
    }

    private void send(String key, String eventId, String payload) {
        ProducerRecord<String, byte[]> rec = new ProducerRecord<>(TOPIC, key, payload.getBytes(StandardCharsets.UTF_8));
        rec.headers().add(new RecordHeader("event_id", eventId.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event_type", "originex.test.Event".getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("tenant_id", "44444444-4444-4444-4444-444444444444".getBytes(StandardCharsets.UTF_8)));
        template.send(rec);
    }

    private int count(String key) {
        AtomicInteger c = listener.deliveries.get(key);
        return c == null ? 0 : c.get();
    }

    private Map<String, ConsumerRecord<String, byte[]>> drainDlq(int expected, Duration timeout) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlq-reader", "true", broker);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.put("auto.offset.reset", "earliest");
        Map<String, ConsumerRecord<String, byte[]>> collected = new HashMap<>();
        try (Consumer<String, byte[]> consumer =
                     new DefaultKafkaConsumerFactory<String, byte[]>(props).createConsumer()) {
            consumer.subscribe(java.util.List.of(DLQ));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline && collected.size() < expected) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> r : records) {
                    collected.put(r.key(), r);
                }
            }
        }
        return collected;
    }

    private static String headerValue(ConsumerRecord<String, byte[]> record, String key) {
        var h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Configuration
    @EnableKafka
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, OriginexKafkaErrorHandlingAutoConfiguration.class})
    static class TestApp {
        @Bean
        ObjectMapper regressionObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TestListener testListener(ObjectMapper mapper) {
            return new TestListener(mapper);
        }
    }

    @Component
    static class TestListener {
        final ObjectMapper mapper;
        final ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicBoolean goodProcessed = new java.util.concurrent.atomic.AtomicBoolean(false);

        TestListener(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @KafkaListener(topics = TOPIC, groupId = "regression-consumer",
                containerFactory = "kafkaListenerContainerFactory")
        void handle(ConsumerRecord<String, byte[]> record) {
            deliveries.computeIfAbsent(record.key(), k -> new AtomicInteger()).incrementAndGet();
            // Poison: readJson throws PoisonEventException (non-retryable) on malformed payload.
            var json = KafkaEventEnvelope.readJson(mapper, record);
            String mode = KafkaEventEnvelope.optionalText(json, "mode", "");
            if ("transient".equals(mode)) {
                // Retryable failure — not in the not-retryable set, so it consumes the retry budget.
                throw new IllegalStateException("simulated transient failure");
            }
            if ("good".equals(mode)) {
                goodProcessed.set(true);
            }
        }
    }
}
