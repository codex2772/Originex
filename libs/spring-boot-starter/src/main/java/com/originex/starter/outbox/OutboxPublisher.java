package com.originex.starter.outbox;

import com.originex.common.tenant.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox Publisher — writes events to the outbox_events table
 * within the SAME transaction as the business operation.
 *
 * <p>This guarantees that either both the business state change AND the event
 * are persisted, or neither is. A separate poller (or Debezium CDC) then
 * reads the outbox and publishes to Kafka.
 *
 * <p>Usage in application services:
 * <pre>
 *   outboxPublisher.publish("LoanApplication", applicationId,
 *       "originex.los.ApplicationSubmitted", tenantId, protobufBytes);
 * </pre>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish an event to the transactional outbox.
     * Must be called within an active transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, UUID aggregateId,
                        String eventType, UUID tenantId,
                        byte[] payload) {
        publish(aggregateType, aggregateId, eventType, tenantId, payload, Map.of());
    }

    /**
     * Publish an event with metadata to the transactional outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, UUID aggregateId,
                        String eventType, UUID tenantId,
                        byte[] payload, Map<String, String> metadata) {

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            metadataJson = "{}";
        }

        UUID eventId = UUID.randomUUID();
        OutboxEventJpaEntity entity = OutboxEventJpaEntity.from(
                eventId, aggregateType, aggregateId,
                eventType, tenantId, payload, metadataJson
        );

        outboxRepository.save(entity);

        log.debug("Outbox event persisted: eventId={}, type={}, aggregate={}:{}",
                eventId, eventType, aggregateType, aggregateId);
    }
}
