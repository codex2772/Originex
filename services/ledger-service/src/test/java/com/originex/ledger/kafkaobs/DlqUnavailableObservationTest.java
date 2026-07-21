package com.originex.ledger.kafkaobs;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STEP 0 / PRE-STAGE-2 OBSERVATION: names the DLQ-unavailable failure mode explicitly.
 *
 * Wires the REAL error-handling stack we intend to ship — DefaultErrorHandler +
 * DeadLetterPublishingRecoverer with setFailIfSendResultIsError(true) — but points the
 * recoverer's producer at a DEAD broker (localhost:1). The source topic is a healthy
 * embedded broker; only the DLQ destination is unwritable. This isolates exactly the
 * question: "source readable, DLQ send times out repeatedly — what happens to the loop?"
 *
 * We observe: (1) is the poison record dropped (silent loss) or kept (redelivered)?
 * (2) does a good record queued behind it on the same partition make progress, or is
 * the partition blocked while the DLQ is down? (3) is each cycle bounded by the send
 * timeout (a loop of bounded attempts) or a hung thread?
 */
@SpringBootTest(
        classes = DlqUnavailableObservationTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.group-id=obs-dlqfail-group",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer"
        }
)
@org.springframework.kafka.test.context.EmbeddedKafka(partitions = 1, topics = {"obs.dlqfail.topic"})
class DlqUnavailableObservationTest {

    private static final Logger log = LoggerFactory.getLogger(DlqUnavailableObservationTest.class);
    private static final String TOPIC = "obs.dlqfail.topic";

    @org.springframework.beans.factory.annotation.Autowired
    KafkaTemplate<String, byte[]> template;

    @org.springframework.beans.factory.annotation.Autowired
    FailingListener listener;

    @Test
    void observeDlqUnavailable() throws Exception {
        long start = System.currentTimeMillis();

        // poison: listener always throws. good: would succeed IF the partition ever advances.
        template.send(TOPIC, "poison-key", "poison".getBytes());
        template.send(TOPIC, "good-key", "good".getBytes());
        template.flush();

        // Observe for a bounded window while the DLQ (dead broker) keeps rejecting sends.
        Thread.sleep(14000);
        long elapsed = System.currentTimeMillis() - start;

        int poison = listener.deliveries.getOrDefault("poison-key", new AtomicInteger(0)).get();
        int good = listener.deliveries.getOrDefault("good-key", new AtomicInteger(0)).get();

        log.warn("=========== DLQ-UNAVAILABLE OBSERVATION RESULTS ===========");
        log.warn("poison-key delivery count (redeliveries while DLQ down) = {}", poison);
        log.warn("good-key   delivery count (0 => partition BLOCKED)      = {}", good);
        log.warn("good record processed?                                  = {}", good > 0);
        log.warn("window elapsed ms (test returns => no hung thread)      = {}", elapsed);
        log.warn("interpretation: poison>1 => NOT dropped (no silent loss); good==0 => partition");
        log.warn("held until DLQ writable again (self-heals on broker recovery, same-cluster).");
        log.warn("===========================================================");
    }

    @Configuration
    @EnableKafka
    @Import(KafkaAutoConfiguration.class)
    static class TestApp {

        /** Recoverer producer aimed at a DEAD broker so every DLQ send fails/times out fast. */
        @Bean
        KafkaOperations<Object, Object> deadDlqTemplate() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
            props.put(ProducerConfig.RETRIES_CONFIG, 0);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        /** The exact handler shape Stage 2 will ship: retry a little, then DLQ, fail-on-send-error. */
        @Bean
        CommonErrorHandler errorHandler(KafkaOperations<Object, Object> deadDlqTemplate) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadDlqTemplate,
                    (rec, ex) -> new org.apache.kafka.common.TopicPartition(rec.topic() + ".dlq", -1));
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(6));
            // 0 retries -> recover immediately, so each cycle = one DLQ send attempt against the dead broker
            return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0));
        }

        @Bean
        FailingListener failingListener() {
            return new FailingListener();
        }
    }

    @Component
    static class FailingListener {
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> deliveries = new java.util.concurrent.ConcurrentHashMap<>();

        @KafkaListener(topics = TOPIC, groupId = "obs-dlqfail-group",
                containerFactory = "kafkaListenerContainerFactory")
        void handle(ConsumerRecord<String, byte[]> record) {
            String key = record.key();
            int n = deliveries.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if ("poison-key".equals(key)) {
                log.warn("listener threw on poison offset={} (delivery #{})", record.offset(), n);
                throw new RuntimeException("simulated permanent poison failure");
            }
            log.warn("listener PROCESSED good record offset={} (delivery #{})", record.offset(), n);
        }
    }
}
