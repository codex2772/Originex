package com.originex.starter.kafka;

import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;

import static com.originex.starter.kafka.KafkaSerdeDefaultsEnvironmentPostProcessor.VALUE_DESERIALIZER_PROPERTY;
import static com.originex.starter.kafka.KafkaSerdeDefaultsEnvironmentPostProcessor.VALUE_SERIALIZER_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kafka serde defaults — byte[] value serde for the outbox transport")
class KafkaSerdeDefaultsEnvironmentPostProcessorTest {

    private final KafkaSerdeDefaultsEnvironmentPostProcessor processor =
            new KafkaSerdeDefaultsEnvironmentPostProcessor();

    @Test
    @DisplayName("defaults the value serde to byte[] on both producer and consumer")
    void defaultsByteArrayValueSerde() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(VALUE_SERIALIZER_PROPERTY))
                .isEqualTo("org.apache.kafka.common.serialization.ByteArraySerializer");
        assertThat(environment.getProperty(VALUE_DESERIALIZER_PROPERTY))
                .isEqualTo("org.apache.kafka.common.serialization.ByteArrayDeserializer");
    }

    @Test
    @DisplayName("is registered in META-INF/spring.factories so Spring actually runs it")
    void isRegisteredForDiscovery() throws Exception {
        // Without this, the class is inert: the post-processor never runs, no serde default
        // is contributed, and every unconfigured service keeps failing the outbox path —
        // while every other test here still passes, because they exercise the class directly.
        // EnvironmentPostProcessor must be declared in spring.factories (the .imports
        // mechanism is for AutoConfiguration only).
        Enumeration<URL> resources =
                getClass().getClassLoader().getResources("META-INF/spring.factories");

        boolean registered = false;
        while (resources.hasMoreElements()) {
            Properties properties = new Properties();
            try (InputStream in = resources.nextElement().openStream()) {
                properties.load(in);
            }
            String declared = properties.getProperty(EnvironmentPostProcessor.class.getName());
            if (declared != null
                    && declared.contains(KafkaSerdeDefaultsEnvironmentPostProcessor.class.getName())) {
                registered = true;
                break;
            }
        }

        assertThat(registered)
                .as("KafkaSerdeDefaultsEnvironmentPostProcessor must be declared under "
                        + EnvironmentPostProcessor.class.getName() + " in META-INF/spring.factories")
                .isTrue();
    }

    @Test
    @DisplayName("the property names actually bind to KafkaProperties' value serde")
    void propertyNamesBindToKafkaProperties() {
        // The other tests prove the properties are contributed and discoverable. This proves
        // the last link: that VALUE_SERIALIZER_PROPERTY / VALUE_DESERIALIZER_PROPERTY are the
        // exact keys Spring Boot binds onto KafkaProperties. Without this, a renamed/misspelt
        // key would leave the post-processor running, every test green, and KafkaProperties
        // falling back to its String default — the outbox path still broken. The constants
        // supply the input; the assertion checks the concrete serde class Spring resolved, so
        // a wrong key surfaces as the String default rather than ByteArray.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withPropertyValues(
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        VALUE_SERIALIZER_PROPERTY
                                + "=org.apache.kafka.common.serialization.ByteArraySerializer",
                        VALUE_DESERIALIZER_PROPERTY
                                + "=org.apache.kafka.common.serialization.ByteArrayDeserializer")
                .run(context -> {
                    KafkaProperties props = context.getBean(KafkaProperties.class);
                    assertThat(props.getProducer().getValueSerializer())
                            .as("the poller sends byte[] values, so the producer must use ByteArraySerializer")
                            .isEqualTo(ByteArraySerializer.class);
                    assertThat(props.getConsumer().getValueDeserializer())
                            .as("consumers read byte[] values, so the consumer must use ByteArrayDeserializer")
                            .isEqualTo(ByteArrayDeserializer.class);
                });
    }

    @Test
    @DisplayName("explicit value serde wins — the default never overrides configuration")
    void explicitConfigurationWins() {
        MockEnvironment environment = new MockEnvironment();
        // A higher-precedence source, as a service's application.yml would be (lms, payment,
        // and notification declare these explicitly, to the same value).
        environment.getPropertySources().addFirst(new MapPropertySource(
                "explicit", Map.of(
                        VALUE_SERIALIZER_PROPERTY,
                        "org.apache.kafka.common.serialization.StringSerializer")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(VALUE_SERIALIZER_PROPERTY))
                .as("contributed as a default (lowest precedence), so explicit config still wins")
                .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
    }
}
