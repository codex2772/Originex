package com.originex.notification.adapter.out.persistence;

import com.originex.notification.domain.model.NotificationChannel;
import com.originex.notification.domain.model.NotificationTemplate;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_templates",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id","trigger_type","channel","language"}))
public class NotificationTemplateJpaEntity {

    @Id @Column(name = "template_id") private UUID templateId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private com.originex.notification.domain.model.NotificationTrigger trigger;
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false) private NotificationChannel channel;
    @Column(name = "language", nullable = false) private String language;
    @Column(name = "subject") private String subject;
    @Column(name = "body", nullable = false, columnDefinition = "TEXT") private String body;
    @Column(name = "active") private boolean active;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    public static NotificationTemplateJpaEntity fromDomain(NotificationTemplate t) {
        NotificationTemplateJpaEntity e = new NotificationTemplateJpaEntity();
        e.templateId = t.getTemplateId();
        e.tenantId = t.getTenantId();
        e.trigger = t.getTrigger();
        e.channel = t.getChannel();
        e.language = t.getLanguage();
        e.subject = t.getSubject();
        e.body = t.getBody();
        e.active = t.isActive();
        e.createdAt = t.getCreatedAt() != null ? t.getCreatedAt() : Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public NotificationTemplate toDomain() {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId(templateId);
        t.setTenantId(tenantId);
        t.setTrigger(trigger);
        t.setChannel(channel);
        t.setLanguage(language);
        t.setSubject(subject);
        t.setBody(body);
        t.setActive(active);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(updatedAt);
        return t;
    }

    protected NotificationTemplateJpaEntity() {}
}
