package com.originex.starter.outbox;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the outbox_events table.
 * Every service that uses the Originex starter gets this entity automatically.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static OutboxEventJpaEntity from(UUID eventId, String aggregateType, UUID aggregateId,
                                            String eventType, UUID tenantId, byte[] payload,
                                            String metadataJson) {
        OutboxEventJpaEntity e = new OutboxEventJpaEntity();
        e.eventId = eventId;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.tenantId = tenantId;
        e.payload = payload;
        e.metadataJson = metadataJson != null ? metadataJson : "{}";
        e.status = "PENDING";
        e.createdAt = Instant.now();
        return e;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    // Accessors
    public UUID getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public UUID getTenantId() { return tenantId; }
    public byte[] getPayload() { return payload; }
    public String getMetadataJson() { return metadataJson; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    protected OutboxEventJpaEntity() {}
}
