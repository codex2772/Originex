package com.originex.starter.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the outbox poller wiring — the inverse of what {@code 7a3ebbd}
 * checks. {@code 7a3ebbd}'s tests prove the poller is <b>absent</b> when Kafka is off
 * the classpath (so a Kafka-free service still boots). This proves it is <b>present</b>
 * when Kafka <i>is</i> available — the case that was silently broken, so no application
 * event ever reached Kafka.
 *
 * <p><b>What broke and what this guards.</b> The poller's
 * {@code @ConditionalOnBean(KafkaTemplate.class)} is evaluated while
 * {@code OutboxAutoConfiguration} is processed. Auto-configurations with no ordering
 * hint are sorted by fully-qualified class name as a tiebreak, and
 * {@code com.originex.starter.outbox.OutboxAutoConfiguration} sorts before
 * {@code org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration}
 * ({@code com} &lt; {@code org}). So without
 * {@code @AutoConfigureAfter(KafkaAutoConfiguration)} the condition ran before
 * {@code kafkaTemplate} existed, the poller was skipped, and it never re-evaluated.
 * Remove the {@code after = KafkaAutoConfiguration.class} on
 * {@code OutboxAutoConfiguration} and this test fails.
 *
 * <p>Real JPA (H2, {@code ddl-auto=none}) is used so the poller's
 * {@code OutboxEventRepository} dependency resolves — the entity's {@code jsonb} column
 * has no H2 equivalent, but the test asserts bean presence, not DB I/O, so the schema is
 * never touched. {@code KafkaTemplate} comes from {@code KafkaAutoConfiguration} on
 * purpose: that is the ordering-sensitive dependency under test, not something the test
 * supplies.
 */
@DisplayName("Outbox poller wiring — present when Kafka is available")
class OutboxPollerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    KafkaAutoConfiguration.class,
                    OutboxAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:outboxwiring;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.jpa.hibernate.ddl-auto=none",
                    "spring.kafka.bootstrap-servers=localhost:9092");

    @Test
    @DisplayName("outboxPoller bean is created when KafkaTemplate is on the context")
    void pollerWiresWhenKafkaPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(org.springframework.kafka.core.KafkaTemplate.class);
            assertThat(ctx).hasBean("outboxPoller");
        });
    }

    @Test
    @DisplayName("the publisher (write side) is present regardless — it needs only JPA")
    void publisherWiresWithoutKafkaDependency() {
        runner.run(ctx -> assertThat(ctx).hasBean("outboxPublisher"));
    }
}
