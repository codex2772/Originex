package com.originex.lms.adapter.in.kafka.payment;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
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
 * LMS consumer for Payment Service events — closes the full payment loop.
 *
 * <p>Event flow handled:
 * <ul>
 *   <li>{@code DisbursementCompleted} → record disbursement confirmation on loan</li>
 *   <li>{@code PaymentReceived} → trigger repayment allocation on loan</li>
 *   <li>{@code PaymentFailed} → flag loan for manual intervention</li>
 * </ul>
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final LoanUseCase loanUseCase;
    private final InboxEventRepository inboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(LoanUseCase loanUseCase,
                                InboxEventRepository inboxRepository,
                                ObjectMapper objectMapper) {
        this.loanUseCase = loanUseCase;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "originex.payments.orders.events",
            groupId = "lms-payment-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handlePaymentEvent(ConsumerRecord<String, byte[]> record) {
        String eventId  = extractHeader(record, "event_id");
        String eventType = extractHeader(record, "event_type");
        String tenantId = extractHeader(record, "tenant_id");

        if (eventId == null || eventType == null || tenantId == null) {
            log.warn("Missing headers on payment event, skipping. offset={}", record.offset());
            return;
        }

        UUID eventUuid = UUID.fromString(eventId);
        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate payment event, skipping: eventId={}", eventId);
            return;
        }

        try {
            TenantContextHolder.set(TenantContext.of(tenantId, tenantId));
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventId);

            switch (eventType) {
                case "originex.payments.DisbursementCompleted" -> handleDisbursementCompleted(tenantId, record.value());
                case "originex.payments.PaymentReceived"       -> handlePaymentReceived(tenantId, record.value());
                case "originex.payments.PaymentFailed"         -> handlePaymentFailed(tenantId, record.value());
                default -> log.debug("Ignoring unhandled payment event: {}", eventType);
            }

            inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));

        } catch (Exception e) {
            log.error("Failed to process payment event: eventId={}, type={}", eventId, eventType, e);
            throw new RuntimeException("Failed to process payment event: " + eventId, e);
        } finally {
            TenantContextHolder.clear();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }

    /** Disbursement confirmed — record UTR on loan */
    private void handleDisbursementCompleted(String tenantId, byte[] payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String loanId = json.get("loan_id").asText();
        String utr = json.has("utr") ? json.get("utr").asText() : "";
        String paymentOrderId = json.get("payment_order_id").asText();

        loanUseCase.confirmDisbursementByPayment(
                UUID.fromString(tenantId), UUID.fromString(loanId),
                UUID.fromString(paymentOrderId), utr
        );
        log.info("Disbursement confirmed on loan: loanId={}, utr={}", loanId, utr);
    }

    /** Repayment received — allocate against loan schedule */
    private void handlePaymentReceived(String tenantId, byte[] payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String loanId = json.get("loan_id").asText();
        String amount = json.get("amount").asText();
        String currency = json.has("currency") ? json.get("currency").asText() : "INR";
        String paymentType = json.has("payment_type") ? json.get("payment_type").asText() : "REPAYMENT_COLLECTION";

        // Only process repayment types (not disbursements echoed back)
        if ("DISBURSEMENT".equals(paymentType)) return;

        loanUseCase.allocateRepaymentFromPayment(
                UUID.fromString(tenantId), UUID.fromString(loanId), amount, currency
        );
        log.info("Repayment allocated from payment event: loanId={}, amount={}", loanId, amount);
    }

    /** Payment failed — log for manual review / collections */
    private void handlePaymentFailed(String tenantId, byte[] payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String loanId = json.get("loan_id").asText();
        String reason = json.has("failure_reason") ? json.get("failure_reason").asText() : "Unknown";
        log.warn("Payment failed for loan: loanId={}, reason={}", loanId, reason);
        // Future: update DPD, trigger collections workflow
    }

    private String extractHeader(ConsumerRecord<String, byte[]> record, String key) {
        var header = record.headers().lastHeader(key);
        return header != null ? new String(header.value()) : null;
    }
}
