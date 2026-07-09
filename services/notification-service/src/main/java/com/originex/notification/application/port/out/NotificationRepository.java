package com.originex.notification.application.port.out;

import com.originex.notification.domain.model.NotificationRequest;
import com.originex.notification.domain.model.NotificationTemplate;
import com.originex.notification.domain.model.NotificationTrigger;
import com.originex.notification.domain.model.NotificationChannel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    NotificationRequest save(NotificationRequest request);
    Optional<NotificationRequest> findById(UUID tenantId, UUID notificationId);
    boolean existsBySourceEventId(String sourceEventId);
    List<NotificationRequest> findPendingRetries(int maxResults);
}
