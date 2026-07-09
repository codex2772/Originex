package com.originex.starter.outbox;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for inbox_events table (idempotent consumer pattern).
 */
@Entity
@Table(name = "inbox_events")
public class InboxEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public static InboxEventJpaEntity of(UUID eventId, String eventType) {
        InboxEventJpaEntity e = new InboxEventJpaEntity();
        e.eventId = eventId;
        e.eventType = eventType;
        e.processedAt = Instant.now();
        return e;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }

    protected InboxEventJpaEntity() {}
}
