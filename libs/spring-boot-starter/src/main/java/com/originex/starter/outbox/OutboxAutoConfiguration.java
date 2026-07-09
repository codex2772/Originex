package com.originex.starter.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for the Transactional Outbox pattern.
 * Activates when JPA and Kafka are both on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.persistence.Entity")
@EnableJpaRepositories(basePackageClasses = OutboxEventRepository.class)
@EntityScan(basePackageClasses = OutboxEventJpaEntity.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    public OutboxPublisher outboxPublisher(OutboxEventRepository outboxRepository,
                                          ObjectMapper objectMapper) {
        return new OutboxPublisher(outboxRepository, objectMapper);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    public OutboxPoller outboxPoller(OutboxEventRepository outboxRepository,
                                    KafkaTemplate<String, byte[]> kafkaTemplate) {
        return new OutboxPoller(outboxRepository, kafkaTemplate);
    }
}
