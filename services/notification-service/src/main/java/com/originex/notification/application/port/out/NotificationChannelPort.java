package com.originex.notification.application.port.out;

import com.originex.notification.domain.model.NotificationChannel;

/**
 * Anti-Corruption Layer port — send a message via a specific channel.
 * Each channel adapter implements this interface.
 */
public interface NotificationChannelPort {

    NotificationChannel channel();

    DispatchResult send(DispatchRequest request);

    record DispatchRequest(
            String recipientPhone,
            String recipientEmail,
            String recipientName,
            String subject,
            String body,
            String templateCode,
            String correlationId
    ) {}

    record DispatchResult(
            boolean success,
            String providerReference,
            String failureReason
    ) {}
}
