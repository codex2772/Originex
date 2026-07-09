package com.originex.notification.application.port.out;

import com.originex.notification.domain.model.NotificationChannel;
import com.originex.notification.domain.model.NotificationTemplate;
import com.originex.notification.domain.model.NotificationTrigger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository {
    Optional<NotificationTemplate> findTemplate(UUID tenantId, NotificationTrigger trigger,
                                                 NotificationChannel channel, String language);
    Optional<NotificationTemplate> findTemplate(UUID tenantId, NotificationTrigger trigger,
                                                 NotificationChannel channel);
    List<NotificationChannel> getConfiguredChannels(UUID tenantId, NotificationTrigger trigger);
    NotificationTemplate save(NotificationTemplate template);
}
