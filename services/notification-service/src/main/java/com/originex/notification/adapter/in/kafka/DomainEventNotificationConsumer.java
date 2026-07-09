package com.originex.notification.adapter.in.kafka;

import com.originex.notification.application.service.NotificationApplicationService;
import com.originex.notification.application.service.NotificationApplicationService.DispatchCommand;
import com.originex.notification.domain.service.EventToNotificationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
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
            containerFactory = "kafkaListenerContainerFactory"
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

        } catch (Exception e) {
            log.error("Failed to process notification for eventId={}, type={}", eventId, eventType, e);
            // Do NOT rethrow — notification failures should not block business events
        } finally {
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
