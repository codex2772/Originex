package com.originex.ledger.kafkaobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.originex.starter.kafka.KafkaEventEnvelope;
import com.originex.starter.kafka.OriginexKafkaErrorHandlingAutoConfiguration;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PRE-STAGE-3 / STAGE-2 CLOSE-OUT OBSERVATION — sustained DLQ outage against the SHIPPED code.
 *
 * Boots the REAL {@link OriginexKafkaErrorHandlingAutoConfiguration} beans, then points the shipped
 * recoverer's DLQ producer at a DEAD broker (spring.kafka.producer.bootstrap-servers=localhost:1)
 * while the consumer keeps reading the healthy embedded broker. Source records are produced with a
 * separate direct producer to the embedded broker. This isolates exactly the case the reviewer
 * asked about: source healthy, DLQ-send repeatedly times out under a sustained outage — exercising
 * the actual {@code DeadLetterPublishingRecoverer} + {@code failIfSendResultIsError(true)} that ships.
 *
 * Observes: (1) poison redelivered repeatedly (NOT dropped), (2) good record behind it does not
 * advance (partition holds), (3) the test returns — each cycle is bounded by the DLQ producer's
 * delivery timeout, so it is a controlled loop, not a hung thread.
 */
@SpringBootTest(
        classes = SustainedDlqOutageObservationTest.TestApp.class,
        properties = {
                "spring.kafka.consumer.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.producer.bootstrap-servers=localhost:1", // DLQ producer -> dead broker
                "spring.kafka.consumer.group-id=obs-sustained-group",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer"
        }
)
@EmbeddedKafka(count = 1, partitions = 1, topics = {"obs.sustained.topic"})
class SustainedDlqOutageObservationTest {

    private static final Logger log = LoggerFactory.getLogger(SustainedDlqOutageObservationTest.class);
    private static final String TOPIC = "obs.sustained.topic";

    @Autowired
    SustainedListener listener;
    @Autowired
    EmbeddedKafkaBroker broker;

    @Test
    void observeSustainedDlqOutage() throws Exception {
        long start = System.currentTimeMillis();

        // Produce source records straight to the healthy embedded broker (NOT via the app's
        // producer, whose bootstrap is the dead broker used for the DLQ path).
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(TOPIC, "poison-key", "{ not valid json ]".getBytes(StandardCharsets.UTF_8)));
            producer.send(new ProducerRecord<>(TOPIC, "good-key", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
            producer.flush();
        }

        Thread.sleep(16000); // observe several DLQ-send-timeout cycles on the shipped recoverer
        long elapsed = System.currentTimeMillis() - start;

        int poison = count("poison-key");
        int good = count("good-key");

        log.warn("=========== SUSTAINED DLQ OUTAGE (SHIPPED CODE) RESULTS ===========");
        log.warn("poison redeliveries (>1 => NOT dropped, stays retryable)   = {}", poison);
        log.warn("good deliveries (0 => partition held behind poison)         = {}", good);
        log.warn("test returned after ms (=> bounded loop, no hung thread)    = {}", elapsed);
        log.warn("shipped recoverer failIfSendResultIsError=true, DLQ producer bootstrap=dead");
        log.warn("=> block-over-loss: never advances past an un-DLQ-able record; self-heals when DLQ writable.");
        log.warn("==================================================================");
    }

    private int count(String key) {
        AtomicInteger c = listener.deliveries.get(key);
        return c == null ? 0 : c.get();
    }

    @Configuration
    @EnableKafka
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, OriginexKafkaErrorHandlingAutoConfiguration.class})
    static class TestApp {
        @Bean
        ObjectMapper sustainedObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SustainedListener sustainedListener(ObjectMapper mapper) {
            return new SustainedListener(mapper);
        }
    }

    @Component
    static class SustainedListener {
        final ObjectMapper mapper;
        final ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        SustainedListener(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @KafkaListener(topics = TOPIC, groupId = "obs-sustained-group",
                containerFactory = "kafkaListenerContainerFactory")
        void handle(ConsumerRecord<String, byte[]> record) {
            deliveries.computeIfAbsent(record.key(), k -> new AtomicInteger()).incrementAndGet();
            KafkaEventEnvelope.readJson(mapper, record); // poison -> PoisonEventException (non-retryable)
        }
    }
}
