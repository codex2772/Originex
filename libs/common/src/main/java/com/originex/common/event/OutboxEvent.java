package com.originex.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox event record — persisted in the outbox_events table within the same
 * transaction as the business operation. A poller or CDC connector reads this
 * table and publishes to Kafka, guaranteeing at-least-once delivery.
 */
public class OutboxEvent {

    private UUID eventId;
    private String aggregateType;
    private UUID aggregateId;
    private String eventType;
    private UUID tenantId;
    private byte[] payload;
    private Map<String, String> metadata;
    private String status;
    private Instant createdAt;
    private Instant publishedAt;

    public static OutboxEvent create(DomainEvent domainEvent, byte[] serializedPayload) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.eventId = UUID.randomUUID();
        outbox.aggregateType = domainEvent.getAggregateType();
        outbox.aggregateId = UUID.fromString(domainEvent.getAggregateId());
        outbox.eventType = domainEvent.getEventType();
        outbox.tenantId = UUID.fromString(domainEvent.getTenantId());
        outbox.payload = serializedPayload;
        outbox.metadata = domainEvent.getMetadata();
        outbox.status = "PENDING";
        outbox.createdAt = Instant.now();
        return outbox;
    }

    public static OutboxEvent createRaw(String aggregateType, UUID aggregateId,
                                        String eventType, UUID tenantId,
                                        byte[] payload, Map<String, String> metadata) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.eventId = UUID.randomUUID();
        outbox.aggregateType = aggregateType;
        outbox.aggregateId = aggregateId;
        outbox.eventType = eventType;
        outbox.tenantId = tenantId;
        outbox.payload = payload;
        outbox.metadata = metadata != null ? metadata : Map.of();
        outbox.status = "PENDING";
        outbox.createdAt = Instant.now();
        return outbox;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public void markFailed() {
        this.status = "FAILED";
    }

    // Accessors
    public UUID getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public UUID getTenantId() { return tenantId; }
    public byte[] getPayload() { return payload; }
    public Map<String, String> getMetadata() { return metadata; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    // For JPA reconstruction
    protected OutboxEvent() {}
    public void setEventId(UUID id) { this.eventId = id; }
    public void setAggregateType(String s) { this.aggregateType = s; }
    public void setAggregateId(UUID id) { this.aggregateId = id; }
    public void setEventType(String s) { this.eventType = s; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setPayload(byte[] b) { this.payload = b; }
    public void setMetadata(Map<String, String> m) { this.metadata = m; }
    public void setStatus(String s) { this.status = s; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setPublishedAt(Instant i) { this.publishedAt = i; }
}
