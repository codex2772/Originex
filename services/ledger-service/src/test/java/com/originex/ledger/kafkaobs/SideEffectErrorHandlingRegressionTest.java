package com.originex.ledger.kafkaobs;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STAGE 3 REGRESSION TEST — the lenient (side-effect) and strict (business) policies coexist, and
 * adding the lenient factory does NOT change the strict factory's behavior.
 *
 * Two listeners run in one context against the real starter auto-config:
 *  - a BUSINESS listener on the default {@code kafkaListenerContainerFactory} (strict), and
 *  - a SIDE-EFFECT listener on {@code sideEffectKafkaListenerContainerFactory} (lenient, like notification).
 *
 * Both throw a retryable exception. Asserts:
 *  1. Side-effect: exactly 1 attempt (0 retries) → routed to the single notification DLQ.
 *  2. Business (unaffected): 4 attempts (1 + 3 retries) → routed to its OWN per-topic DLQ.
 *  3. Routing separation: neither record lands in the other's DLQ.
 */
@SpringBootTest(
        classes = SideEffectErrorHandlingRegressionTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.group-id=stage3-consumer",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
                "originex.kafka.side-effect-dlq=originex.notifications.deadletter.dlq"
        }
)
@EmbeddedKafka(partitions = 1, topics = {
        "originex.biz.events", "originex.biz.events.dlq",
        "originex.side.events", "originex.notifications.deadletter.dlq"
})
class SideEffectErrorHandlingRegressionTest {

    private static final Logger log = LoggerFactory.getLogger(SideEffectErrorHandlingRegressionTest.class);
    private static final String BIZ = "originex.biz.events";
    private static final String BIZ_DLQ = "originex.biz.events.dlq";
    private static final String SIDE = "originex.side.events";
    private static final String NOTIF_DLQ = "originex.notifications.deadletter.dlq";

    @Autowired
    KafkaTemplate<String, byte[]> template;
    @Autowired
    Listeners listeners;
    @Autowired
    EmbeddedKafkaBroker broker;

    @Test
    void lenientAndStrictPoliciesCoexist() {
        send(BIZ, "biz");
        send(SIDE, "side");
        template.flush();

        List<ConsumerRecord<String, byte[]>> dlq = drain(List.of(BIZ_DLQ, NOTIF_DLQ), 2, Duration.ofSeconds(30));

        List<String> bizDlqKeys = keysIn(dlq, BIZ_DLQ);
        List<String> notifDlqKeys = keysIn(dlq, NOTIF_DLQ);

        log.warn("=========== STAGE 3 REGRESSION RESULTS ===========");
        log.warn("side-effect deliveries = {} (expect 1: 0 retries)", count("side"));
        log.warn("business    deliveries = {} (expect 4: 1 + 3 retries, UNAFFECTED)", count("biz"));
        log.warn("business DLQ ({}) keys       = {}", BIZ_DLQ, bizDlqKeys);
        log.warn("notification DLQ ({}) keys  = {}", NOTIF_DLQ, notifDlqKeys);
        log.warn("==================================================");

        // 1. side-effect: 0 retries -> 1 attempt -> notification DLQ
        assertThat(count("side")).as("side-effect delivered once (0 retries)").isEqualTo(1);
        assertThat(notifDlqKeys).as("side-effect routed to notification DLQ").contains("side");

        // 2. business unaffected: still 4 attempts -> its own per-topic DLQ
        assertThat(count("biz")).as("business still retries 1 + 3 (unaffected)").isEqualTo(4);
        assertThat(bizDlqKeys).as("business routed to per-topic DLQ").contains("biz");

        // 3. routing separation
        assertThat(notifDlqKeys).as("business record not in notification DLQ").doesNotContain("biz");
        assertThat(bizDlqKeys).as("side-effect record not in business DLQ").doesNotContain("side");
    }

    private void send(String topic, String key) {
        ProducerRecord<String, byte[]> rec =
                new ProducerRecord<>(topic, key, "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        rec.headers().add(new RecordHeader("event_id", ("id-" + key).getBytes(StandardCharsets.UTF_8)));
        template.send(rec);
    }

    private int count(String key) {
        AtomicInteger c = listeners.deliveries.get(key);
        return c == null ? 0 : c.get();
    }

    private static List<String> keysIn(List<ConsumerRecord<String, byte[]>> records, String topic) {
        List<String> keys = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> r : records) {
            if (r.topic().equals(topic)) keys.add(r.key());
        }
        return keys;
    }

    private List<ConsumerRecord<String, byte[]>> drain(List<String> topics, int expected, Duration timeout) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("stage3-dlq-reader", "true", broker);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.put("auto.offset.reset", "earliest");
        List<ConsumerRecord<String, byte[]>> collected = new ArrayList<>();
        try (Consumer<String, byte[]> consumer =
                     new DefaultKafkaConsumerFactory<String, byte[]>(props).createConsumer()) {
            consumer.subscribe(topics);
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline && collected.size() < expected) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(collected::add);
            }
        }
        return collected;
    }

    @Configuration
    @EnableKafka
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, OriginexKafkaErrorHandlingAutoConfiguration.class})
    static class TestApp {
        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }

    @Component
    static class Listeners {
        final ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        @KafkaListener(topics = BIZ, groupId = "stage3-biz", containerFactory = "kafkaListenerContainerFactory")
        void business(ConsumerRecord<String, byte[]> record) {
            deliveries.computeIfAbsent(record.key(), k -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("simulated business transient failure");
        }

        @KafkaListener(topics = SIDE, groupId = "stage3-side", containerFactory = "sideEffectKafkaListenerContainerFactory")
        void sideEffect(ConsumerRecord<String, byte[]> record) {
            deliveries.computeIfAbsent(record.key(), k -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("simulated side-effect failure");
        }
    }
}
