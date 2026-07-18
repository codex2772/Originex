package com.originex.starter.kafka;

import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;

/**
 * Defaults the Kafka <b>value</b> serde to {@code byte[]} for every service, so the
 * transactional-outbox transport works without per-service Kafka configuration.
 *
 * <p>Why this exists: the outbox poller sends {@code ProducerRecord<String, byte[]>}
 * (the serialized protobuf payload) and every consumer reads
 * {@code ConsumerRecord<String, byte[]>}. But Spring Boot's {@code KafkaProperties}
 * defaults both {@code value-serializer} and {@code value-deserializer} to the
 * {@code String} variants. Only three services (lms, payment, notification) overrode
 * that in their own {@code application.yml}; the rest inherited the {@code String}
 * default, which breaks <i>both</i> ends of the outbox path:
 * <ul>
 *   <li><b>Producer</b> — the poller hands the {@code StringSerializer} a {@code byte[]}
 *       and it throws {@code SerializationException: Can't convert value of class [B to
 *       class ...StringSerializer}. Observed on customer (CustomerRegistered) and los
 *       (ApplicationSubmitted): the event stays {@code PENDING} and never reaches Kafka.</li>
 *   <li><b>Consumer</b> — the {@code StringDeserializer} produces a {@code String} that
 *       the {@code ConsumerRecord<String, byte[]>} listener casts, throwing
 *       {@code ClassCastException: String cannot be cast to [B}. Observed on ledger
 *       (the lms→ledger hop).</li>
 * </ul>
 *
 * <p>This defect was latent until the outbox poller began actually running (it had not
 * been wiring at all — see {@link com.originex.starter.outbox.OutboxAutoConfiguration}).
 * Once the poller ran, serialization was attempted for the first time and the gap
 * surfaced. Rather than copy the four serde lines into six more {@code application.yml}
 * files, the correct transport default belongs in the starter.
 *
 * <p><b>Keys are deliberately left alone.</b> The outbox key is a {@code String}
 * (the partition key) on both ends, and Spring Boot already defaults key serde to
 * {@code String} — so only the value serde is the defect, and only it is defaulted here.
 *
 * <p>Contributed as <b>default</b> properties (lowest precedence): a service that
 * declares its own {@code spring.kafka.*.value-*} — as lms, payment, and notification
 * do — still wins, binding the identical value with no behavioural change.
 */
public class KafkaSerdeDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String VALUE_SERIALIZER_PROPERTY =
            "spring.kafka.producer.value-serializer";
    static final String VALUE_DESERIALIZER_PROPERTY =
            "spring.kafka.consumer.value-deserializer";

    static final String BYTE_ARRAY_SERIALIZER =
            "org.apache.kafka.common.serialization.ByteArraySerializer";
    static final String BYTE_ARRAY_DESERIALIZER =
            "org.apache.kafka.common.serialization.ByteArrayDeserializer";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        DefaultPropertiesPropertySource.addOrMerge(
                Map.of(
                        VALUE_SERIALIZER_PROPERTY, BYTE_ARRAY_SERIALIZER,
                        VALUE_DESERIALIZER_PROPERTY, BYTE_ARRAY_DESERIALIZER),
                environment.getPropertySources());
    }
}
