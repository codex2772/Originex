package com.originex.lms.adapter.in.kafka;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.application.port.in.LoanUseCase.CreateLoanCommand;
import com.originex.starter.outbox.InboxEventJpaEntity;
import com.originex.starter.outbox.InboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Kafka consumer for LOS events — triggers loan creation on DisbursementRequested.
 * Implements inbox idempotency pattern.
 */
@Component
public class DisbursementRequestedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DisbursementRequestedConsumer.class);

    private final LoanUseCase loanUseCase;
    private final InboxEventRepository inboxRepository;
    private final ObjectMapper objectMapper;

    public DisbursementRequestedConsumer(LoanUseCase loanUseCase,
                                         InboxEventRepository inboxRepository,
                                         ObjectMapper objectMapper) {
        this.loanUseCase = loanUseCase;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${originex.kafka.topics.disbursement-requested:originex.los.applications.events}",
            groupId = "lms-disbursement-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleDisbursementRequested(ConsumerRecord<String, byte[]> record) {
        String eventId = extractHeader(record, "event_id");
        String tenantId = extractHeader(record, "tenant_id");
        String eventType = extractHeader(record, "event_type");

        // Only process DisbursementRequested events
        if (!"originex.los.DisbursementRequested".equals(eventType)) {
            return;
        }

        if (eventId == null || tenantId == null) {
            log.warn("Missing required headers, skipping. offset={}", record.offset());
            return;
        }

        UUID eventUuid = UUID.fromString(eventId);

        // Inbox idempotency check
        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate DisbursementRequested, skipping: eventId={}", eventId);
            return;
        }

        log.info("Processing DisbursementRequested: eventId={}", eventId);

        try {
            TenantContextHolder.set(TenantContext.of(tenantId, tenantId));
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventId);

            // Deserialize payload
            JsonNode json = objectMapper.readTree(record.value());

            CreateLoanCommand command = new CreateLoanCommand(
                    UUID.fromString(tenantId),
                    UUID.fromString(json.get("customer_id").asText()),
                    UUID.fromString(json.get("application_id").asText()),
                    json.get("product_code").asText(),
                    json.get("sanctioned_amount").asText(),
                    json.get("interest_rate").asText(),
                    json.has("rate_type") ? json.get("rate_type").asText() : "FIXED",
                    json.get("tenure_months").asInt(),
                    json.get("emi").asText(),
                    json.has("currency") ? json.get("currency").asText() : "INR"
            );

            loanUseCase.createLoan(command);

            // Mark in inbox as processed
            inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));

            log.info("Loan created from DisbursementRequested: eventId={}", eventId);

        } catch (Exception e) {
            log.error("Failed to process DisbursementRequested: eventId={}", eventId, e);
            throw new RuntimeException("Failed to process DisbursementRequested", e);
        } finally {
            TenantContextHolder.clear();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }

    private String extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        var header = record.headers().lastHeader(key);
        return header != null ? new String(header.value()) : null;
    }
}
