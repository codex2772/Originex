package com.originex.lms.adapter.in.kafka.payment;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.starter.kafka.KafkaEventEnvelope;
import com.originex.starter.outbox.InboxEventJpaEntity;
import com.originex.starter.outbox.InboxEventRepository;
import com.originex.starter.security.MachineActorContext;
import com.originex.starter.security.OriginexScopes;
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
 *
 * <p>Payload/header defects surface as {@link com.originex.starter.kafka.PoisonEventException}
 * (via {@link KafkaEventEnvelope}) and are routed straight to the DLQ; transient failures propagate
 * and are retried by the shared error handler.
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
        String eventType = KafkaEventEnvelope.requireHeader(record, "event_type");
        UUID eventUuid = KafkaEventEnvelope.requireUuidHeader(record, "event_id");
        String tenantId = KafkaEventEnvelope.requireHeader(record, "tenant_id");

        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate payment event, skipping: eventId={}", eventUuid);
            return;
        }

        try {
            TenantContextHolder.set(TenantContext.of(tenantId, tenantId));
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventUuid.toString());

            switch (eventType) {
                case "originex.payments.DisbursementCompleted" ->
                        handleDisbursementCompleted(tenantId, KafkaEventEnvelope.readJson(objectMapper, record));
                case "originex.payments.PaymentReceived" ->
                        handlePaymentReceived(tenantId, KafkaEventEnvelope.readJson(objectMapper, record));
                case "originex.payments.PaymentFailed" ->
                        handlePaymentFailed(tenantId, KafkaEventEnvelope.readJson(objectMapper, record));
                default -> {
                    log.debug("Ignoring unhandled payment event: {}", eventType);
                    return; // not our event — do not record in inbox
                }
            }

            inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));

        } finally {
            TenantContextHolder.clear();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }

    /** Disbursement confirmed — record UTR on loan */
    private void handleDisbursementCompleted(String tenantId, JsonNode json) {
        String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
        String utr = KafkaEventEnvelope.optionalText(json, "utr", "");
        String paymentOrderId = KafkaEventEnvelope.requiredText(json, "payment_order_id");

        invokeAsMachine(OriginexScopes.LOANS_DISBURSE, () ->
                loanUseCase.confirmDisbursementByPayment(
                        UUID.fromString(tenantId), UUID.fromString(loanId),
                        UUID.fromString(paymentOrderId), utr
                ));
        log.info("Disbursement confirmed on loan: loanId={}, utr={}", loanId, utr);
    }

    /** Repayment received — allocate against loan schedule */
    private void handlePaymentReceived(String tenantId, JsonNode json) {
        String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
        String amount = KafkaEventEnvelope.requiredText(json, "amount");
        String currency = KafkaEventEnvelope.optionalText(json, "currency", "INR");
        String paymentType = KafkaEventEnvelope.optionalText(json, "payment_type", "REPAYMENT_COLLECTION");

        // Only process repayment types (not disbursements echoed back)
        if ("DISBURSEMENT".equals(paymentType)) return;

        invokeAsMachine(OriginexScopes.LOANS_SERVICE, () ->
                loanUseCase.allocateRepaymentFromPayment(
                        UUID.fromString(tenantId), UUID.fromString(loanId), amount, currency
                ));
        log.info("Repayment allocated from payment event: loanId={}, amount={}", loanId, amount);
    }

    /**
     * Runs a single use-case call under a machine identity granted <b>exactly</b> {@code scope} and nothing
     * else. The tenant is already bound for the whole message (outer {@code TenantContextHolder}); this layers
     * the minimal capability per branch, so a DisbursementCompleted message carries only {@code loans:disburse}
     * and a PaymentReceived message only {@code loans:service}. Neither branch can reach {@code loans:create} or
     * the fraud-sensitive {@code loans:repay-manual} — that isolation is the point (see KI-19). The security
     * context is cleared immediately after, never leaking onto the pooled Kafka listener thread.
     */
    private void invokeAsMachine(String scope, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                MachineActorContext.machineAuthentication(scope));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Payment failed — log for manual review / collections */
    private void handlePaymentFailed(String tenantId, JsonNode json) {
        String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
        String reason = KafkaEventEnvelope.optionalText(json, "failure_reason", "Unknown");
        log.warn("Payment failed for loan: loanId={}, reason={}", loanId, reason);
        // Future: update DPD, trigger collections workflow
    }
}
