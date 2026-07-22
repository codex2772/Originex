package com.originex.notification.adapter.in.kafka;

import com.originex.notification.application.service.NotificationApplicationService;
import com.originex.notification.application.service.NotificationApplicationService.DispatchCommand;
import com.originex.notification.domain.service.EventToNotificationMapper;
import com.originex.starter.security.MachineActorContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Universal Kafka consumer — listens to ALL domain event topics
 * and routes events that have a notification trigger.
 *
 * <p>Topics consumed:
 * <ul>
 *   <li>originex.customer.customers.events</li>
 *   <li>originex.los.applications.events</li>
 *   <li>originex.lms.loans.events</li>
 *   <li>originex.payments.orders.events</li>
 * </ul>
 *
 * <p>The consumer is idempotent — duplicate events are silently skipped.
 * No inbox table needed here because the application service handles deduplication
 * via sourceEventId.
 *
 * <p>This is a side-effect consumer: it runs on the lenient
 * {@code sideEffectKafkaListenerContainerFactory} (0 Kafka retries → single
 * {@code originex.notifications.deadletter.dlq}), so a dispatch failure is dead-lettered
 * visibly rather than silently dropped, and never blocks business consumers.
 */
@Component
public class DomainEventNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventNotificationConsumer.class);

    private final NotificationApplicationService notificationService;
    private final EventToNotificationMapper eventMapper;
    private final ObjectMapper objectMapper;

    public DomainEventNotificationConsumer(NotificationApplicationService notificationService,
                                           EventToNotificationMapper eventMapper,
                                           ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                    "originex.customer.customers.events",
                    "originex.los.applications.events",
                    "originex.lms.loans.events",
                    "originex.payments.orders.events"
            },
            groupId = "notification-event-consumer",
            containerFactory = "sideEffectKafkaListenerContainerFactory"
    )
    @Transactional
    public void handle(ConsumerRecord<String, byte[]> record) {
        String eventId   = extractHeader(record, "event_id");
        String eventType = extractHeader(record, "event_type");
        String tenantId  = extractHeader(record, "tenant_id");

        if (eventId == null || eventType == null || tenantId == null) {
            log.debug("Missing headers, skipping: offset={}", record.offset());
            return;
        }

        // Quick filter — does this event type have a notification trigger?
        if (eventMapper.mapTrigger(eventType).isEmpty()) {
            return;
        }

        log.debug("Notification consumer received: eventType={}, eventId={}", eventType, eventId);

        try {
            // ── Ceremonial machine actor — mechanism uniformity ONLY, NOT an authorization gate. ──
            //
            // Every other consumer on the platform establishes a *minimally-scoped* machine principal
            // because it invokes a @PreAuthorize'd use-case port. notification has none: it is a pure
            // internal side-effect sink (Kafka in → channel dispatch out), with no use-case port, no REST
            // surface, and nothing a human or external caller can trigger. There is no authorization
            // boundary to cross and nothing to authorize — so this principal carries ZERO scopes. It
            // exists only so the unit of work runs under a named `system:machine` identity instead of
            // anonymous (audit / mechanism consistency), and so this path is provably unaffected when
            // originex.security.enabled=true turns method security on (see the RLS IT, which now runs
            // under enforcement). Do NOT read this as "authz enforced," and do NOT add a @PreAuthorize to
            // dress it up as a gate: that would fake a boundary the source does not have.
            //
            // We set ONLY the SecurityContext — never MachineActorContext.establish(...), which would also
            // (re-)bind the tenant. The tenant is already bound by the starter's TenantRecordInterceptor
            // from the tenant_id header, before this @Transactional method; re-binding it here is exactly
            // the redundant in-consumer set lms is on the hook to drop. The interceptor owns the tenant
            // lifecycle (and clears it on success/failure); we own only this ceremonial security context
            // and MUST clear it in the finally below so it can never leak onto the pooled listener thread.
            SecurityContextHolder.getContext().setAuthentication(MachineActorContext.machineAuthentication());
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventId);

            JsonNode payload = parsePayload(record.value());
            Map<String, String> variables = eventMapper.extractVariables(eventType, payload);

            // Extract recipient info from payload
            String customerId = getField(payload, "customer_id");
            String loanId     = getField(payload, "loan_id");

            // Note: In production, Customer Service would be queried for phone/email.
            // For now we use whatever the event payload carries (works for sandbox).
            String phone = getField(payload, "phone");
            String email = getField(payload, "email");
            String name  = getField(payload, "customer_name");

            notificationService.dispatch(new DispatchCommand(
                    UUID.fromString(tenantId),
                    customerId, loanId,
                    phone, email, name,
                    "en",           // language — future: lookup from customer profile
                    eventType, eventId,
                    variables
            ));

            // Failures are no longer swallowed: an unexpected dispatch error now propagates to the
            // lenient (side-effect) error handler → 0 retries → notification DLQ. This consumer runs
            // in its own group, so a failure here never blocks business consumers. Retry budget is 0
            // deliberately — transient gateway blips are handled inside dispatch() (markFailed + the
            // app-level retry poller); a Kafka retry would only re-attempt the class of failure that
            // can double-send an already-sent notification (see KI-11).
        } finally {
            // Same thread-boundary discipline the scoped consumers use — it still applies to the
            // ceremonial context, and on the exception path too: clear it here (the interceptor clears
            // tenant, not the SecurityContext) so no principal survives onto the next record.
            SecurityContextHolder.clearContext();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }

    private JsonNode parsePayload(byte[] payload) {
        if (payload == null || payload.length == 0) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Could not parse event payload as JSON: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String getField(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode val = node.get(field);
        return val.isNull() ? null : val.asText();
    }

    private String extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        var header = record.headers().lastHeader(key);
        return header != null ? new String(header.value()) : null;
    }
}
