package com.originex.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbox event record — persisted when an incoming event is processed.
 * Used for idempotent consumer pattern: skip if event_id already exists.
 */
public class InboxEvent {

    private UUID eventId;
    private String eventType;
    private Instant processedAt;

    public static InboxEvent of(UUID eventId, String eventType) {
        InboxEvent inbox = new InboxEvent();
        inbox.eventId = eventId;
        inbox.eventType = eventType;
        inbox.processedAt = Instant.now();
        return inbox;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }

    protected InboxEvent() {}
    public void setEventId(UUID id) { this.eventId = id; }
    public void setEventType(String s) { this.eventType = s; }
    public void setProcessedAt(Instant i) { this.processedAt = i; }
}
