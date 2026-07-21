package com.originex.ledger.kafkaobs;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STEP 0 OBSERVATION HARNESS (Phase 4 investigation — not a permanent regression test).
 *
 * Boots the REAL Spring Boot auto-configured {@code kafkaListenerContainerFactory} — the exact
 * same bean every Originex consumer references via containerFactory="kafkaListenerContainerFactory".
 * No custom error handler is defined anywhere in this context (mirrors the services), so whatever
 * error-handling behavior we observe here is precisely what production consumers do today.
 *
 * A listener that mimics the real consumers (reads bytes, objectMapper.readTree, throws on failure)
 * receives a poison (bad-JSON) record followed by a good record on the SAME single partition.
 * We observe: (1) how many times the poison record is delivered, (2) whether/when the following
 * good record is processed (i.e. does the poison block the partition), (3) retry timing.
 */
@SpringBootTest(
        classes = PoisonMessageObservationTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.group-id=obs-poison-group",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer"
        }
)
@EmbeddedKafka(partitions = 1, topics = {"obs.poison.topic"})
class PoisonMessageObservationTest {

    private static final Logger log = LoggerFactory.getLogger(PoisonMessageObservationTest.class);
    private static final String TOPIC = "obs.poison.topic";

    @org.springframework.beans.factory.annotation.Autowired
    KafkaTemplate<String, byte[]> template;

    @org.springframework.beans.factory.annotation.Autowired
    ObsListener listener;

    @Test
    void observePoisonThenGood() throws Exception {
        long start = System.currentTimeMillis();

        // 1) poison: not valid JSON -> objectMapper.readTree() throws, like the real consumers
        template.send(TOPIC, "poison-key", "{ this is : not valid json ]".getBytes());
        // 2) good: valid JSON delivered AFTER the poison, same single partition
        template.send(TOPIC, "good-key", "{\"ok\":true}".getBytes());
        template.flush();

        // Wait for the good record to be processed, OR for a generous ceiling to elapse.
        boolean goodProcessed = listener.goodLatch.await(60, TimeUnit.SECONDS);
        // Give any trailing poison redeliveries a moment to settle before we read counters.
        Thread.sleep(2000);

        long elapsed = System.currentTimeMillis() - start;

        log.warn("================= STEP 0 OBSERVATION RESULTS =================");
        log.warn("poison-key delivery count (listener invocations for poison) = {}",
                listener.deliveries.getOrDefault("poison-key", new AtomicInteger(0)).get());
        log.warn("good-key delivery count                                     = {}",
                listener.deliveries.getOrDefault("good-key", new AtomicInteger(0)).get());
        log.warn("good record processed (partition NOT permanently blocked)?  = {}", goodProcessed);
        log.warn("processed-good keys                                         = {}", listener.processedGood);
        log.warn("total elapsed ms (proxy for retry backoff)                  = {}", elapsed);
        log.warn("first poison delivery ts offset ms                          = {}", listener.firstPoisonTs.get() - start);
        log.warn("last  poison delivery ts offset ms                          = {}", listener.lastPoisonTs.get() - start);
        log.warn("=============================================================");
    }

    @Configuration
    @EnableKafka
    @Import(KafkaAutoConfiguration.class)
    static class TestApp {
        @Bean
        ObjectMapper observationObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ObsListener obsListener(ObjectMapper mapper) {
            return new ObsListener(mapper);
        }
    }

    @Component
    static class ObsListener {
        final ObjectMapper mapper;
        final ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        final List<String> processedGood = new CopyOnWriteArrayList<>();
        final CountDownLatch goodLatch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicLong firstPoisonTs = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong lastPoisonTs = new java.util.concurrent.atomic.AtomicLong(0);

        ObsListener(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @KafkaListener(topics = TOPIC, groupId = "obs-poison-group",
                containerFactory = "kafkaListenerContainerFactory")
        void handle(ConsumerRecord<String, byte[]> record) throws Exception {
            String key = record.key();
            deliveries.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            // mimic the real consumers: parse the payload as JSON inside the listener
            try {
                mapper.readTree(record.value());
            } catch (Exception parseFailure) {
                long now = System.currentTimeMillis();
                firstPoisonTs.compareAndSet(0, now);
                lastPoisonTs.set(now);
                log.warn("listener threw on key={} offset={} (delivery #{})",
                        key, record.offset(), deliveries.get(key).get());
                throw new RuntimeException("Failed to process poison record", parseFailure);
            }
            processedGood.add(key);
            if ("good-key".equals(key)) {
                goodLatch.countDown();
            }
        }
    }
}
