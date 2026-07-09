package com.originex.template.application.port.out;

import com.originex.common.event.DomainEvent;

/**
 * Outbound port — defines event publishing operations.
 * Implemented by the Kafka adapter using the Outbox pattern.
 */
public interface EventPublisher {

    /**
     * Publish a domain event (via transactional outbox).
     * The event is persisted in the outbox table within the same DB transaction
     * as the business operation. A CDC connector or poller publishes it to Kafka.
     */
    void publish(DomainEvent event);
}
