package com.originex.payment.adapter.in.kafka;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.payment.application.port.in.PaymentUseCase;
import com.originex.payment.application.port.in.PaymentUseCase.InitiateDisbursementCommand;
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
        String eventId = extractHeader(record, "event_id");
        String eventType = extractHeader(record, "event_type");
        String tenantId = extractHeader(record, "tenant_id");

        // Only process events we care about
        if (!"originex.lms.LoanDisbursed".equals(eventType)) return;
        if (eventId == null || tenantId == null) {
            log.warn("Missing headers on LMS event, skipping. offset={}", record.offset());
            return;
        }

        UUID eventUuid = UUID.fromString(eventId);
        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate LoanDisbursed, skipping: eventId={}", eventId);
            return;
        }

        log.info("Processing LoanDisbursed for payment initiation: eventId={}", eventId);

        try {
            TenantContextHolder.set(TenantContext.of(tenantId, tenantId));
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventId);

            JsonNode json = objectMapper.readTree(record.value());
            String loanId = json.get("loan_id").asText();
            String amount = json.get("amount").asText();
            String currency = json.has("currency") ? json.get("currency").asText() : "INR";

            // These fields should be present from the LMS LoanDisbursed event payload
            String beneficiaryAccount = json.has("beneficiary_account") ? json.get("beneficiary_account").asText() : null;
            String beneficiaryIfsc = json.has("beneficiary_ifsc") ? json.get("beneficiary_ifsc").asText() : null;
            String beneficiaryName = json.has("beneficiary_name") ? json.get("beneficiary_name").asText() : "Borrower";
            String beneficiaryBank = json.has("beneficiary_bank") ? json.get("beneficiary_bank").asText() : null;
            String customerId = json.has("customer_id") ? json.get("customer_id").asText() : null;

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

        } catch (Exception e) {
            log.error("Failed to process LoanDisbursed event: eventId={}", eventId, e);
            throw new RuntimeException("Failed to process LoanDisbursed: " + eventId, e);
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
