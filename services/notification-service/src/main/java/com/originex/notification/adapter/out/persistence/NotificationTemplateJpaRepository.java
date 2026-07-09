package com.originex.notification.adapter.out.persistence;

import com.originex.notification.domain.model.NotificationChannel;
import com.originex.notification.domain.model.NotificationTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateJpaRepository extends JpaRepository<NotificationTemplateJpaEntity, UUID> {

    @Query("SELECT t FROM NotificationTemplateJpaEntity t WHERE t.tenantId = :tid AND t.trigger = :trigger AND t.channel = :channel AND t.language = :lang AND t.active = true")
    Optional<NotificationTemplateJpaEntity> findByTenantTriggerChannelLang(UUID tid, NotificationTrigger trigger, NotificationChannel channel, String lang);

    @Query("SELECT t FROM NotificationTemplateJpaEntity t WHERE t.tenantId = :tid AND t.trigger = :trigger AND t.channel = :channel AND t.active = true ORDER BY t.language")
    List<NotificationTemplateJpaEntity> findByTenantTriggerChannel(UUID tid, NotificationTrigger trigger, NotificationChannel channel);

    @Query("SELECT DISTINCT t.channel FROM NotificationTemplateJpaEntity t WHERE t.tenantId = :tid AND t.trigger = :trigger AND t.active = true")
    List<NotificationChannel> findConfiguredChannels(UUID tid, NotificationTrigger trigger);
}
