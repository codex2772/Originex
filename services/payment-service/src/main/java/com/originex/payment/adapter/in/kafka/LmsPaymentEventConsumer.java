package com.originex.payment.adapter.in.kafka;

import com.originex.common.tenant.TenantContext;
import com.originex.starter.security.MachineActorContext;
import com.originex.starter.security.OriginexScopes;
import com.originex.payment.application.port.in.PaymentUseCase;
import com.originex.payment.application.port.in.PaymentUseCase.InitiateDisbursementCommand;
import com.originex.starter.kafka.KafkaEventEnvelope;
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
 * Kafka consumer — listens for LMS loan events and auto-initiates disbursements.
 *
 * <p>When LMS confirms a loan is disbursed (i.e., LMS internal state change after
 * DisbursementRequested was processed), Payment Service initiates the actual
 * fund transfer to the borrower's bank account.
 *
 * <p>Event flow:
 * LOS → DisbursementRequested → LMS creates Loan → LMS publishes LoanDisbursed
 * → Payment Service consumes LoanDisbursed → initiates NEFT/RTGS/IMPS transfer
 * → Payment Service publishes DisbursementCompleted → LMS updates loan status
 *
 * <p>Payload/header defects surface as {@link com.originex.starter.kafka.PoisonEventException}
 * (via {@link KafkaEventEnvelope}) and are routed straight to the DLQ; transient failures propagate
 * and are retried by the shared error handler.
 */
@Component
public class LmsPaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LmsPaymentEventConsumer.class);

    private final PaymentUseCase paymentUseCase;
    private final InboxEventRepository inboxRepository;
    private final ObjectMapper objectMapper;

    public LmsPaymentEventConsumer(PaymentUseCase paymentUseCase,
                                   InboxEventRepository inboxRepository,
                                   ObjectMapper objectMapper) {
        this.paymentUseCase = paymentUseCase;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "originex.lms.loans.events",
            groupId = "payment-lms-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleLmsEvent(ConsumerRecord<String, byte[]> record) {
        String eventType = KafkaEventEnvelope.requireHeader(record, "event_type");

        // Only process events we care about
        if (!"originex.lms.LoanDisbursed".equals(eventType)) return;

        UUID eventUuid = KafkaEventEnvelope.requireUuidHeader(record, "event_id");
        String tenantId = KafkaEventEnvelope.requireHeader(record, "tenant_id");

        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate LoanDisbursed, skipping: eventId={}", eventUuid);
            return;
        }

        log.info("Processing LoanDisbursed for payment initiation: eventId={}", eventUuid);

        try {
            // Tenant identity (RLS) + minimal machine authorization (payments:disburse only) — the one
            // op this consumer invokes — established together and cleared together (see MachineActorContext).
            MachineActorContext.establish(TenantContext.of(tenantId, tenantId), OriginexScopes.PAYMENTS_DISBURSE);
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventUuid.toString());

            JsonNode json = KafkaEventEnvelope.readJson(objectMapper, record);
            String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
            String amount = KafkaEventEnvelope.requiredText(json, "amount");
            String currency = KafkaEventEnvelope.optionalText(json, "currency", "INR");

            // These fields should be present from the LMS LoanDisbursed event payload
            String beneficiaryAccount = KafkaEventEnvelope.optionalText(json, "beneficiary_account", null);
            String beneficiaryIfsc = KafkaEventEnvelope.optionalText(json, "beneficiary_ifsc", null);
            String beneficiaryName = KafkaEventEnvelope.optionalText(json, "beneficiary_name", "Borrower");
            String beneficiaryBank = KafkaEventEnvelope.optionalText(json, "beneficiary_bank", null);
            String customerId = KafkaEventEnvelope.optionalText(json, "customer_id", null);

            if (beneficiaryAccount == null || beneficiaryIfsc == null) {
                log.error("LoanDisbursed event missing beneficiary details: loanId={}", loanId);
                inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));
                return;
            }

            paymentUseCase.initiateDisbursement(new InitiateDisbursementCommand(
                    UUID.fromString(tenantId),
                    UUID.fromString(loanId),
                    customerId != null ? UUID.fromString(customerId) : null,
                    amount, currency,
                    beneficiaryAccount, beneficiaryIfsc, beneficiaryName, beneficiaryBank,
                    null // auto-select rail based on amount
            ));

            inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));
            log.info("Disbursement initiated from LoanDisbursed: loanId={}, amount={}", loanId, amount);

        } finally {
            MachineActorContext.clear();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }
}
