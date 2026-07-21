package com.originex.starter.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Outbox Poller — periodically reads PENDING events from the outbox table
 * and publishes them to Kafka. Marks them as PUBLISHED on success.
 *
 * <p>This is the "polling publisher" pattern. For higher throughput,
 * replace with Debezium CDC (reads PostgreSQL WAL directly).
 *
 * <p>Polling interval: 500ms (configurable).
 * Batch size: 100 events per poll.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public OutboxPoller(OutboxEventRepository outboxRepository,
                        KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${originex.outbox.poll-interval-ms:500}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEventJpaEntity> pendingEvents =
                outboxRepository.findPendingEvents(PageRequest.of(0, BATCH_SIZE));

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEventJpaEntity event : pendingEvents) {
            try {
                String topic = resolveTopicFromEventType(event.getEventType());
                String partitionKey = event.getAggregateId().toString();

                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, partitionKey, event.getPayload());

                // Add standard headers for downstream consumers
                record.headers().add(new RecordHeader("event_id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("event_type", event.getEventType().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("aggregate_type", event.getAggregateType().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("aggregate_id", event.getAggregateId().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("tenant_id", event.getTenantId().toString().getBytes(StandardCharsets.UTF_8)));

                kafkaTemplate.send(record).whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish outbox event: eventId={}, type={}",
                                event.getEventId(), event.getEventType(), ex);
                    }
                });

                // Mark as published within the same transaction
                outboxRepository.markPublished(event.getEventId(), Instant.now());

                log.debug("Outbox event published: eventId={}, topic={}, key={}",
                        event.getEventId(), topic, partitionKey);

            } catch (Exception e) {
                log.error("Error processing outbox event: eventId={}", event.getEventId(), e);
                // Don't mark as published — will be retried on next poll
            }
        }

        log.info("Outbox poll: published {} events", pendingEvents.size());
    }

    /**
     * Cleanup: delete events published more than 7 days ago.
     */
    @Scheduled(cron = "0 0 2 * * *") // Daily at 2am
    @Transactional
    public void cleanupPublishedEvents() {
        Instant cutoff = Instant.now().minusSeconds(7 * 24 * 60 * 60);
        int deleted = outboxRepository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup: deleted {} published events older than 7 days", deleted);
        }
    }

    /**
     * Resolve Kafka topic from event type.
     * Convention: originex.{domain}.{aggregate}.events
     */
    private String resolveTopicFromEventType(String eventType) {
        // eventType format: "originex.los.ApplicationSubmitted"
        // Topic format: "originex.los.applications.events"
        if (eventType.startsWith("originex.los.")) return "originex.los.applications.events";
        if (eventType.startsWith("originex.lms.")) return "originex.lms.loans.events";
        if (eventType.startsWith("originex.customer.")) return "originex.customer.customers.events";
        if (eventType.startsWith("originex.ledger.")) return "originex.ledger.journal-entries.events";
        if (eventType.startsWith("originex.payments.")) return "originex.payments.orders.events";
        // bre-service, partner-integration-service, and notification-service don't publish via
        // outbox yet, but route to their reserved topics (see infra/kafka/topics.yaml) now so a
        // future publisher can't silently land in the unrouted-events fallback below.
        if (eventType.startsWith("originex.bre.")) return "originex.bre.evaluations.events";
        if (eventType.startsWith("originex.partner.")) return "originex.partner.integration-requests.events";
        if (eventType.startsWith("originex.notification.")) return "originex.notifications.requests.events";

        // Fallback: use event type segments
        log.warn("Unknown event type for topic resolution: {}", eventType);
        return "originex.platform.unrouted-events";
    }
}
