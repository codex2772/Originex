package com.originex.starter.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for the Transactional Outbox pattern.
 *
 * <p>The publisher (write side) needs only JPA. The poller (publish side) needs
 * Kafka, and is therefore isolated in a nested {@link KafkaPollerConfiguration}
 * gated on {@code @ConditionalOnClass(KafkaTemplate.class)} — see the note there.
 *
 * <p><b>Scanning scope.</b> The outbox entities/repositories live in this starter
 * package (not under a service's base package), so they must be scanned explicitly.
 * But {@code @EntityScan}/{@code @EnableJpaRepositories} <i>replace</i> Spring Boot's
 * default (the service's auto-configuration package) rather than add to it — so
 * pointing them only at this package would suppress every service's own entities and
 * repositories. They are therefore scoped to the shared {@code com.originex} root,
 * which covers both this starter's outbox package and each service's own packages
 * (services carry only platform + their own code on the classpath).
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.persistence.Entity")
@EnableJpaRepositories(basePackages = "com.originex")
@EntityScan(basePackages = "com.originex")
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    public OutboxPublisher outboxPublisher(OutboxEventRepository outboxRepository,
                                          ObjectMapper objectMapper) {
        return new OutboxPublisher(outboxRepository, objectMapper);
    }

    /**
     * The poller is the only part that touches Kafka. It lives in a nested
     * {@code @Configuration} rather than a bean method on the outer class for a
     * subtle but load-bearing reason: a class-level {@code import} plus a
     * {@code KafkaTemplate} method parameter force the JVM to load
     * {@code KafkaTemplate} in order to <i>process</i> the outer {@code @Configuration}
     * — before any {@code @Conditional} is evaluated. On a JPA service without
     * {@code spring-kafka} on the classpath (e.g. bre-service, which neither publishes
     * nor consumes) that is a hard {@code NoClassDefFoundError} at context startup,
     * even though the {@code @ConditionalOnBean(KafkaTemplate.class)} was meant to keep
     * the poller optional. Spring evaluates {@code @ConditionalOnClass} on a nested
     * {@code @Configuration} <i>without</i> loading the referenced class (the check is
     * by name), so the KafkaTemplate reference is confined here and never reached when
     * the class is absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.kafka.core.KafkaTemplate.class)
    static class KafkaPollerConfiguration {

        @Bean
        @ConditionalOnBean(org.springframework.kafka.core.KafkaTemplate.class)
        public OutboxPoller outboxPoller(OutboxEventRepository outboxRepository,
                                        org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafkaTemplate) {
            return new OutboxPoller(outboxRepository, kafkaTemplate);
        }
    }
}
