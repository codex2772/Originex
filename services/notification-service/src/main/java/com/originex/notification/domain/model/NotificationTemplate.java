package com.originex.notification.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NotificationTemplate — per-tenant, per-trigger, per-channel, per-language template.
 * Supports variable substitution via {{variable_name}} syntax.
 */
public class NotificationTemplate {

    private UUID templateId;
    private UUID tenantId;
    private NotificationTrigger trigger;
    private NotificationChannel channel;
    private String language;            // en, hi, mr, ta, te
    private String subject;             // For EMAIL
    private String body;                // SMS/WhatsApp text or HTML email body
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Render the template by substituting {{variable}} placeholders. A variable
     * that is not supplied is rendered as empty — a missing value never leaks a
     * raw {{placeholder}} into a customer-facing message.
     */
    public String renderBody(java.util.Map<String, String> variables) {
        String rendered = this.body;
        for (var entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return stripUnresolved(rendered);
    }

    public String renderSubject(java.util.Map<String, String> variables) {
        if (subject == null) return null;
        String rendered = this.subject;
        for (var entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return stripUnresolved(rendered);
    }

    /** Blank any {{placeholder}} left unresolved because its variable was not supplied. */
    private static String stripUnresolved(String rendered) {
        return rendered == null ? null : rendered.replaceAll("\\{\\{[^{}]*\\}\\}", "");
    }

    // Accessors
    public UUID getTemplateId() { return templateId; }
    public UUID getTenantId() { return tenantId; }
    public NotificationTrigger getTrigger() { return trigger; }
    public NotificationChannel getChannel() { return channel; }
    public String getLanguage() { return language; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public NotificationTemplate() {}
    public void setTemplateId(UUID id) { this.templateId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setTrigger(NotificationTrigger t) { this.trigger = t; }
    public void setChannel(NotificationChannel c) { this.channel = c; }
    public void setLanguage(String s) { this.language = s; }
    public void setSubject(String s) { this.subject = s; }
    public void setBody(String s) { this.body = s; }
    public void setActive(boolean b) { this.active = b; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }
}
