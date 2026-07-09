package com.originex.common.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Base class for all domain events in the Originex platform.
 *
 * <p>Every domain event carries standard metadata enabling correlation,
 * tracing, multi-tenancy, and auditing. Subclasses define domain-specific payload.
 *
 * <p>Events are immutable after creation.
 */
public abstract class DomainEvent {

    private final String eventId;
    private final String eventType;
    private final String aggregateType;
    private final String aggregateId;
    private final String tenantId;
    private final Instant occurredAt;
    private final String correlationId;
    private final String causationId;
    private final String actorId;
    private final String actorType;
    private final Map<String, String> metadata;

    protected DomainEvent(Builder<?> builder) {
        this.eventId = builder.eventId != null ? builder.eventId : UUID.randomUUID().toString();
        this.eventType = builder.eventType != null ? builder.eventType : this.getClass().getSimpleName();
        this.aggregateType = Objects.requireNonNull(builder.aggregateType, "aggregateType required");
        this.aggregateId = Objects.requireNonNull(builder.aggregateId, "aggregateId required");
        this.tenantId = Objects.requireNonNull(builder.tenantId, "tenantId required");
        this.occurredAt = builder.occurredAt != null ? builder.occurredAt : Instant.now();
        this.correlationId = builder.correlationId;
        this.causationId = builder.causationId;
        this.actorId = builder.actorId;
        this.actorType = builder.actorType;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getTenantId() { return tenantId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getCorrelationId() { return correlationId; }
    public String getCausationId() { return causationId; }
    public String getActorId() { return actorId; }
    public String getActorType() { return actorType; }
    public Map<String, String> getMetadata() { return metadata; }

    /**
     * Returns the Kafka partition key for this event.
     * Default: aggregateId (ensures ordering per aggregate).
     */
    public String partitionKey() {
        return aggregateId;
    }

    /**
     * Returns the Kafka topic this event should be published to.
     * Override in subclasses if needed. Default convention: originex.{domain}.{aggregate}.events
     */
    public abstract String topic();

    /**
     * Returns the serialized payload bytes for this event.
     * Override in subclasses to provide Protobuf/JSON serialization.
     */
    public byte[] getPayload() {
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Builder base
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    protected abstract static class Builder<T extends Builder<T>> {
        private String eventId;
        private String eventType;
        private String aggregateType;
        private String aggregateId;
        private String tenantId;
        private Instant occurredAt;
        private String correlationId;
        private String causationId;
        private String actorId;
        private String actorType;
        private Map<String, String> metadata;

        public T eventId(String eventId) { this.eventId = eventId; return (T) this; }
        public T eventType(String eventType) { this.eventType = eventType; return (T) this; }
        public T aggregateType(String aggregateType) { this.aggregateType = aggregateType; return (T) this; }
        public T aggregateId(String aggregateId) { this.aggregateId = aggregateId; return (T) this; }
        public T tenantId(String tenantId) { this.tenantId = tenantId; return (T) this; }
        public T occurredAt(Instant occurredAt) { this.occurredAt = occurredAt; return (T) this; }
        public T correlationId(String correlationId) { this.correlationId = correlationId; return (T) this; }
        public T causationId(String causationId) { this.causationId = causationId; return (T) this; }
        public T actorId(String actorId) { this.actorId = actorId; return (T) this; }
        public T actorType(String actorType) { this.actorType = actorType; return (T) this; }

        public T metadata(String key, String value) {
            if (this.metadata == null) this.metadata = new HashMap<>();
            this.metadata.put(key, value);
            return (T) this;
        }
    }
}
