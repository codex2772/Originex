package com.originex.notification.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NotificationRequest Aggregate Root.
 *
 * <p>Represents a request to send a notification to a recipient via one or more
 * channels. Tracks delivery attempts and final delivery status per channel.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Every notification request has a correlation to a domain event (sourceEventId)</li>
 *   <li>Maximum 3 delivery attempts per channel before permanently failed</li>
 *   <li>A successfully delivered notification cannot be retried</li>
 *   <li>All channel dispatch results are logged for audit (RBI requirement)</li>
 * </ul>
 */
public class NotificationRequest {

    private UUID notificationId;
    private UUID tenantId;
    private String customerId;
    private String loanId;
    private String sourceEventId;       // Kafka event_id that triggered this
    private String sourceEventType;     // e.g. originex.lms.LoanDisbursed
    private NotificationTrigger trigger;
    private NotificationStatus status;
    private String recipientPhone;
    private String recipientEmail;
    private String recipientName;
    private String preferredLanguage;   // en, hi, mr, ta, te — for template selection
    private List<ChannelDispatch> dispatches;
    private int retryCount;
    private Instant createdAt;
    private Instant updatedAt;

    // ─── Factory ───

    public static NotificationRequest create(UUID tenantId, NotificationTrigger trigger,
                                              String customerId, String loanId,
                                              String recipientPhone, String recipientEmail,
                                              String recipientName, String language,
                                              String sourceEventId, String sourceEventType) {
        NotificationRequest n = new NotificationRequest();
        n.notificationId = UUID.randomUUID();
        n.tenantId = tenantId;
        n.trigger = trigger;
        n.customerId = customerId;
        n.loanId = loanId;
        n.recipientPhone = recipientPhone;
        n.recipientEmail = recipientEmail;
        n.recipientName = recipientName;
        n.preferredLanguage = language != null ? language : "en";
        n.sourceEventId = sourceEventId;
        n.sourceEventType = sourceEventType;
        n.status = NotificationStatus.PENDING;
        n.dispatches = new ArrayList<>();
        n.retryCount = 0;
        n.createdAt = Instant.now();
        n.updatedAt = Instant.now();
        return n;
    }

    // ─── State Machine ───

    public ChannelDispatch addDispatch(NotificationChannel channel) {
        ChannelDispatch dispatch = ChannelDispatch.pending(this.notificationId, channel);
        this.dispatches.add(dispatch);
        return dispatch;
    }

    public void markDelivered() {
        this.status = NotificationStatus.DELIVERED;
        this.updatedAt = Instant.now();
    }

    public void markPartiallyDelivered() {
        this.status = NotificationStatus.PARTIALLY_DELIVERED;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = NotificationStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void incrementRetry() {
        this.retryCount++;
        this.status = NotificationStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    // ─── Accessors ───
    public UUID getNotificationId() { return notificationId; }
    public UUID getTenantId() { return tenantId; }
    public String getCustomerId() { return customerId; }
    public String getLoanId() { return loanId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getSourceEventType() { return sourceEventType; }
    public NotificationTrigger getTrigger() { return trigger; }
    public NotificationStatus getStatus() { return status; }
    public String getRecipientPhone() { return recipientPhone; }
    public String getRecipientEmail() { return recipientEmail; }
    public String getRecipientName() { return recipientName; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public List<ChannelDispatch> getDispatches() { return dispatches; }
    public int getRetryCount() { return retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // For JPA
    public NotificationRequest() {}
    public void setNotificationId(UUID id) { this.notificationId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setCustomerId(String s) { this.customerId = s; }
    public void setLoanId(String s) { this.loanId = s; }
    public void setSourceEventId(String s) { this.sourceEventId = s; }
    public void setSourceEventType(String s) { this.sourceEventType = s; }
    public void setTrigger(NotificationTrigger t) { this.trigger = t; }
    public void setStatus(NotificationStatus s) { this.status = s; }
    public void setRecipientPhone(String s) { this.recipientPhone = s; }
    public void setRecipientEmail(String s) { this.recipientEmail = s; }
    public void setRecipientName(String s) { this.recipientName = s; }
    public void setPreferredLanguage(String s) { this.preferredLanguage = s; }
    public void setDispatches(List<ChannelDispatch> l) { this.dispatches = l; }
    public void setRetryCount(int i) { this.retryCount = i; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }

    public enum NotificationStatus { PENDING, DELIVERED, PARTIALLY_DELIVERED, FAILED, SUPPRESSED }
}
