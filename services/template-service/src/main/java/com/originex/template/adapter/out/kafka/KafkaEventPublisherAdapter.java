package com.originex.template.adapter.out.kafka;

import com.originex.common.event.DomainEvent;
import com.originex.starter.outbox.OutboxPublisher;
import com.originex.template.application.port.out.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Kafka event publisher adapter — delegates to the shared OutboxPublisher.
 * Events are written to the outbox_events table within the current transaction,
 * then relayed to Kafka by the OutboxPoller.
 */
@Component
public class KafkaEventPublisherAdapter implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisherAdapter.class);

    private final OutboxPublisher outboxPublisher;

    public KafkaEventPublisherAdapter(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        log.debug("Publishing event via outbox: type={}, aggregateId={}",
                event.getEventType(), event.getAggregateId());

        outboxPublisher.publish(
                event.getAggregateType(),
                UUID.fromString(event.getAggregateId()),
                event.getEventType(),
                UUID.fromString(event.getTenantId()),
                event.getPayload() != null ? event.getPayload() : "{}".getBytes(StandardCharsets.UTF_8)
        );
    }
}
