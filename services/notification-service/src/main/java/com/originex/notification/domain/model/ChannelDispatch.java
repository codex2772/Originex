package com.originex.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a single delivery attempt for a specific channel within a NotificationRequest.
 */
public class ChannelDispatch {

    private UUID dispatchId;
    private UUID notificationId;
    private NotificationChannel channel;
    private DispatchStatus status;
    private String providerReference;   // MSG91 request_id, SES message_id, etc.
    private String failureReason;
    private int attemptCount;
    private Instant sentAt;
    private Instant deliveredAt;

    public static ChannelDispatch pending(UUID notificationId, NotificationChannel channel) {
        ChannelDispatch d = new ChannelDispatch();
        d.dispatchId = UUID.randomUUID();
        d.notificationId = notificationId;
        d.channel = channel;
        d.status = DispatchStatus.PENDING;
        d.attemptCount = 0;
        return d;
    }

    public void markSent(String providerReference) {
        this.providerReference = providerReference;
        this.status = DispatchStatus.SENT;
        this.attemptCount++;
        this.sentAt = Instant.now();
    }

    public void markDelivered() {
        this.status = DispatchStatus.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = DispatchStatus.FAILED;
        this.failureReason = reason;
        this.attemptCount++;
    }

    // Accessors
    public UUID getDispatchId() { return dispatchId; }
    public UUID getNotificationId() { return notificationId; }
    public NotificationChannel getChannel() { return channel; }
    public DispatchStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public ChannelDispatch() {}
    public void setDispatchId(UUID id) { this.dispatchId = id; }
    public void setNotificationId(UUID id) { this.notificationId = id; }
    public void setChannel(NotificationChannel c) { this.channel = c; }
    public void setStatus(DispatchStatus s) { this.status = s; }
    public void setProviderReference(String s) { this.providerReference = s; }
    public void setFailureReason(String s) { this.failureReason = s; }
    public void setAttemptCount(int i) { this.attemptCount = i; }
    public void setSentAt(Instant i) { this.sentAt = i; }
    public void setDeliveredAt(Instant i) { this.deliveredAt = i; }

    public enum DispatchStatus { PENDING, SENT, DELIVERED, FAILED, SKIPPED }
}
