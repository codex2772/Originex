package com.originex.notification.adapter.out.persistence;

import com.originex.notification.domain.model.*;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "notification_requests")
public class NotificationRequestJpaEntity {

    @Id @Column(name = "notification_id") private UUID notificationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "customer_id") private String customerId;
    @Column(name = "loan_id") private String loanId;
    @Column(name = "source_event_id", nullable = false, unique = true) private String sourceEventId;
    @Column(name = "source_event_type", nullable = false) private String sourceEventType;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false) private NotificationTrigger trigger;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false) private NotificationRequest.NotificationStatus status;
    @Column(name = "recipient_phone") private String recipientPhone;
    @Column(name = "recipient_email") private String recipientEmail;
    @Column(name = "recipient_name") private String recipientName;
    @Column(name = "preferred_language") private String preferredLanguage;
    @Column(name = "retry_count") private int retryCount;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "notification_id")
    private List<ChannelDispatchJpaEntity> dispatches = new ArrayList<>();

    public static NotificationRequestJpaEntity fromDomain(NotificationRequest n) {
        NotificationRequestJpaEntity e = new NotificationRequestJpaEntity();
        e.notificationId = n.getNotificationId();
        e.tenantId = n.getTenantId();
        e.customerId = n.getCustomerId();
        e.loanId = n.getLoanId();
        e.sourceEventId = n.getSourceEventId();
        e.sourceEventType = n.getSourceEventType();
        e.trigger = n.getTrigger();
        e.status = n.getStatus();
        e.recipientPhone = n.getRecipientPhone();
        e.recipientEmail = n.getRecipientEmail();
        e.recipientName = n.getRecipientName();
        e.preferredLanguage = n.getPreferredLanguage();
        e.retryCount = n.getRetryCount();
        e.createdAt = n.getCreatedAt() != null ? n.getCreatedAt() : Instant.now();
        e.updatedAt = Instant.now();
        if (n.getDispatches() != null) {
            e.dispatches = n.getDispatches().stream()
                    .map(ChannelDispatchJpaEntity::fromDomain)
                    .collect(Collectors.toList());
        }
        return e;
    }

    public NotificationRequest toDomain() {
        NotificationRequest n = new NotificationRequest();
        n.setNotificationId(notificationId);
        n.setTenantId(tenantId);
        n.setCustomerId(customerId);
        n.setLoanId(loanId);
        n.setSourceEventId(sourceEventId);
        n.setSourceEventType(sourceEventType);
        n.setTrigger(trigger);
        n.setStatus(status);
        n.setRecipientPhone(recipientPhone);
        n.setRecipientEmail(recipientEmail);
        n.setRecipientName(recipientName);
        n.setPreferredLanguage(preferredLanguage);
        n.setRetryCount(retryCount);
        n.setCreatedAt(createdAt);
        n.setUpdatedAt(updatedAt);
        if (dispatches != null) {
            n.setDispatches(dispatches.stream().map(ChannelDispatchJpaEntity::toDomain).collect(Collectors.toList()));
        }
        return n;
    }

    protected NotificationRequestJpaEntity() {}
}
