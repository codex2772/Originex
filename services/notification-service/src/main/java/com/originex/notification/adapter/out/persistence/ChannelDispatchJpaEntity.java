package com.originex.notification.adapter.out.persistence;

import com.originex.notification.domain.model.ChannelDispatch;
import com.originex.notification.domain.model.NotificationChannel;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_dispatches")
public class ChannelDispatchJpaEntity {

    @Id @Column(name = "dispatch_id") private UUID dispatchId;
    @Column(name = "notification_id", nullable = false) private UUID notificationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false) private NotificationChannel channel;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false) private ChannelDispatch.DispatchStatus status;
    @Column(name = "provider_reference") private String providerReference;
    @Column(name = "failure_reason") private String failureReason;
    @Column(name = "attempt_count") private int attemptCount;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "delivered_at") private Instant deliveredAt;

    public static ChannelDispatchJpaEntity fromDomain(ChannelDispatch d) {
        ChannelDispatchJpaEntity e = new ChannelDispatchJpaEntity();
        e.dispatchId = d.getDispatchId();
        e.notificationId = d.getNotificationId();
        e.channel = d.getChannel();
        e.status = d.getStatus();
        e.providerReference = d.getProviderReference();
        e.failureReason = d.getFailureReason();
        e.attemptCount = d.getAttemptCount();
        e.sentAt = d.getSentAt();
        e.deliveredAt = d.getDeliveredAt();
        return e;
    }

    public ChannelDispatch toDomain() {
        ChannelDispatch d = new ChannelDispatch();
        d.setDispatchId(dispatchId);
        d.setNotificationId(notificationId);
        d.setChannel(channel);
        d.setStatus(status);
        d.setProviderReference(providerReference);
        d.setFailureReason(failureReason);
        d.setAttemptCount(attemptCount);
        d.setSentAt(sentAt);
        d.setDeliveredAt(deliveredAt);
        return d;
    }

    protected ChannelDispatchJpaEntity() {}
}
