package com.originex.lms.adapter.in.kafka;

import com.originex.common.tenant.TenantContext;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.application.port.in.LoanUseCase.CreateLoanCommand;
import com.originex.starter.kafka.KafkaEventEnvelope;
import com.originex.starter.security.MachineActorContext;
import com.originex.starter.security.OriginexScopes;
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
 *
 * <p>Payload/header defects surface as {@link com.originex.starter.kafka.PoisonEventException}
 * (via {@link KafkaEventEnvelope}) and are routed straight to the DLQ; transient failures propagate
 * and are retried by the shared error handler.
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
        String eventType = KafkaEventEnvelope.requireHeader(record, "event_type");

        // Only process DisbursementRequested events
        if (!"originex.los.DisbursementRequested".equals(eventType)) {
            return;
        }

        UUID eventUuid = KafkaEventEnvelope.requireUuidHeader(record, "event_id");
        String tenantId = KafkaEventEnvelope.requireHeader(record, "tenant_id");

        // Inbox idempotency check
        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate DisbursementRequested, skipping: eventId={}", eventUuid);
            return;
        }

        log.info("Processing DisbursementRequested: eventId={}", eventUuid);

        try {
            // Machine actor: bind the tenant (for RLS) and grant EXACTLY loans:create — the only
            // capability this consumer needs. It can neither disburse, service, nor manually assert a
            // repayment; the fraud-sensitive loans:repay-manual is structurally out of its reach.
            MachineActorContext.establish(TenantContext.of(tenantId, tenantId), OriginexScopes.LOANS_CREATE);
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventUuid.toString());

            // Deserialize payload
            JsonNode json = KafkaEventEnvelope.readJson(objectMapper, record);

            CreateLoanCommand command = new CreateLoanCommand(
                    UUID.fromString(tenantId),
                    KafkaEventEnvelope.requiredUuid(json, "customer_id"),
                    KafkaEventEnvelope.requiredUuid(json, "application_id"),
                    KafkaEventEnvelope.requiredText(json, "product_code"),
                    KafkaEventEnvelope.requiredText(json, "sanctioned_amount"),
                    KafkaEventEnvelope.requiredText(json, "interest_rate"),
                    KafkaEventEnvelope.optionalText(json, "rate_type", "FIXED"),
                    Integer.parseInt(KafkaEventEnvelope.requiredText(json, "tenure_months")),
                    KafkaEventEnvelope.requiredText(json, "emi"),
                    KafkaEventEnvelope.optionalText(json, "currency", "INR"),
                    KafkaEventEnvelope.optionalText(json, "beneficiary_account", null),
                    KafkaEventEnvelope.optionalText(json, "beneficiary_ifsc", null),
                    KafkaEventEnvelope.optionalText(json, "beneficiary_name", null),
                    KafkaEventEnvelope.optionalText(json, "beneficiary_bank", null)
            );

            loanUseCase.createLoan(command);

            // Mark in inbox as processed
            inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));

            log.info("Loan created from DisbursementRequested: eventId={}", eventUuid);

        } finally {
            MachineActorContext.clear();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }
}
