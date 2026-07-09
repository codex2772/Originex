package com.originex.notification.adapter.out.persistence;

import com.originex.notification.application.port.out.NotificationRepository;
import com.originex.notification.application.port.out.NotificationTemplateRepository;
import com.originex.notification.domain.model.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class NotificationPersistenceAdapter implements NotificationRepository, NotificationTemplateRepository {

    private final NotificationRequestJpaRepository requestRepo;
    private final NotificationTemplateJpaRepository templateRepo;

    public NotificationPersistenceAdapter(NotificationRequestJpaRepository requestRepo,
                                          NotificationTemplateJpaRepository templateRepo) {
        this.requestRepo = requestRepo;
        this.templateRepo = templateRepo;
    }

    // ─── NotificationRepository ───

    @Override
    public NotificationRequest save(NotificationRequest r) {
        return requestRepo.save(NotificationRequestJpaEntity.fromDomain(r)).toDomain();
    }

    @Override
    public Optional<NotificationRequest> findById(UUID tenantId, UUID notificationId) {
        return requestRepo.findByTenantAndId(tenantId, notificationId).map(NotificationRequestJpaEntity::toDomain);
    }

    @Override
    public boolean existsBySourceEventId(String sourceEventId) {
        return requestRepo.existsBySourceEventId(sourceEventId);
    }

    @Override
    public List<NotificationRequest> findPendingRetries(int maxResults) {
        return requestRepo.findPendingRetries(PageRequest.of(0, maxResults))
                .stream().map(NotificationRequestJpaEntity::toDomain).toList();
    }

    // ─── NotificationTemplateRepository ───

    @Override
    public Optional<NotificationTemplate> findTemplate(UUID tenantId, NotificationTrigger trigger,
                                                        NotificationChannel channel, String language) {
        return templateRepo.findByTenantTriggerChannelLang(tenantId, trigger, channel, language)
                .map(NotificationTemplateJpaEntity::toDomain);
    }

    @Override
    public Optional<NotificationTemplate> findTemplate(UUID tenantId, NotificationTrigger trigger,
                                                        NotificationChannel channel) {
        return templateRepo.findByTenantTriggerChannel(tenantId, trigger, channel)
                .stream().findFirst().map(NotificationTemplateJpaEntity::toDomain);
    }

    @Override
    public List<NotificationChannel> getConfiguredChannels(UUID tenantId, NotificationTrigger trigger) {
        return templateRepo.findConfiguredChannels(tenantId, trigger);
    }

    @Override
    public NotificationTemplate save(NotificationTemplate template) {
        return templateRepo.save(NotificationTemplateJpaEntity.fromDomain(template)).toDomain();
    }
}
