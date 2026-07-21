package com.originex.payment.application.service;

import com.originex.common.money.Money;
import com.originex.common.tenant.SystemContextHolder;
import com.originex.payment.application.port.in.PaymentUseCase;
import com.originex.payment.application.port.out.NachMandateRepository;
import com.originex.payment.application.port.out.PaymentOrderRepository;
import com.originex.payment.application.port.out.PaymentRailPort;
import com.originex.payment.domain.exception.NachMandateNotFoundException;
import com.originex.payment.domain.exception.PaymentOrderNotFoundException;
import com.originex.payment.domain.model.NachMandate;
import com.originex.payment.domain.model.PaymentOrder;
import com.originex.payment.domain.model.PaymentOrder.PaymentRail;
import com.originex.payment.domain.model.PaymentOrder.PaymentType;
import com.originex.starter.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Payment Application Service — orchestrates the full payment lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Route disbursements to correct rail (NEFT/RTGS/IMPS auto-selected by amount)</li>
 *   <li>Register and trigger NACH mandates for EMI collection</li>
 *   <li>Handle inbound payment notifications</li>
 *   <li>Retry failed payments with exponential backoff</li>
 *   <li>Publish all state changes to Kafka via transactional outbox</li>
 * </ul>
 */
@Service
@Transactional
public class PaymentApplicationService implements PaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);

    // Payment rail auto-selection band boundaries — see selectRail() below for
    // the full business rule these implement. IMPS_MIN_AMOUNT is also the
    // point below which NEFT is used; IMPS_MAX_AMOUNT is also the point above
    // which RTGS is used, so the three rails partition the amount range with
    // no overlap and no gap.
    private static final BigDecimal IMPS_MIN_AMOUNT = new BigDecimal("200000"); // ₹2,00,000
    private static final BigDecimal IMPS_MAX_AMOUNT = new BigDecimal("500000"); // ₹5,00,000

    private final PaymentOrderRepository paymentOrderRepository;
    private final NachMandateRepository nachMandateRepository;
    private final Map<PaymentRail, PaymentRailPort> railAdapters;
    private final OutboxPublisher outboxPublisher;
    /**
     * Self-reference (Spring proxy) used only by {@link #retryFailedPaymentsJob()}
     * to invoke the transactional {@link #retryFailedPayments()} through the proxy
     * — a direct internal call would bypass the transaction advice. {@code @Lazy}
     * breaks the self-referential construction cycle.
     */
    private final PaymentApplicationService self;

    public PaymentApplicationService(PaymentOrderRepository paymentOrderRepository,
                                     NachMandateRepository nachMandateRepository,
                                     List<PaymentRailPort> railPorts,
                                     OutboxPublisher outboxPublisher,
                                     @Lazy PaymentApplicationService self) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.nachMandateRepository = nachMandateRepository;
        this.railAdapters = railPorts.stream()
                .collect(Collectors.toMap(PaymentRailPort::rail, p -> p));
        this.outboxPublisher = outboxPublisher;
        this.self = self;
    }

    @Override
    public PaymentOrder initiateDisbursement(InitiateDisbursementCommand command) {
        log.info("Initiating disbursement: loanId={}, amount={} {}", command.loanId(), command.amount(), command.currency());

        Money amount = Money.of(command.amount(), command.currency());

        // Auto-select payment rail based on amount if not specified
        PaymentRail rail = selectRail(command.preferredRail(), amount);

        PaymentOrder order = PaymentOrder.createDisbursement(
                command.tenantId(), command.loanId(), command.customerId(),
                amount, command.beneficiaryAccountNumber(), command.beneficiaryIfsc(),
                command.beneficiaryName(), command.beneficiaryBankName(), rail
        );

        PaymentOrder saved = paymentOrderRepository.save(order);

        // Submit to payment rail
        submitToRail(saved);

        // Publish DisbursementInitiated event
        outboxPublisher.publish("PaymentOrder", saved.getPaymentOrderId(),
                "originex.payments.DisbursementInitiated", command.tenantId(),
                buildPaymentEventPayload(saved));

        log.info("Disbursement initiated: paymentOrderId={}, rail={}, ref={}",
                saved.getPaymentOrderId(), rail, saved.getPaymentReference());
        return saved;
    }

    @Override
    public NachMandate registerNachMandate(RegisterMandateCommand command) {
        log.info("Registering NACH mandate: loanId={}", command.loanId());

        Money maxAmount = Money.of(command.maxDebitAmount(), command.currency());
        NachMandate mandate = NachMandate.register(
                command.tenantId(), command.loanId(), command.customerId(),
                command.bankAccountNumber(), command.ifscCode(), command.bankName(),
                command.accountHolderName(), maxAmount,
                Instant.parse(command.startDate()), Instant.parse(command.endDate())
        );

        NachMandate saved = nachMandateRepository.save(mandate);

        outboxPublisher.publish("NachMandate", saved.getMandateId(),
                "originex.payments.NachMandateRegistered", command.tenantId(),
                String.format("{\"mandate_id\":\"%s\",\"loan_id\":\"%s\",\"status\":\"%s\"}",
                        saved.getMandateId(), saved.getLoanId(), saved.getStatus())
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public PaymentOrder triggerNachCollection(TriggerCollectionCommand command) {
        log.info("Triggering NACH collection: loanId={}, amount={}", command.loanId(), command.amount());

        NachMandate mandate = nachMandateRepository.findById(command.tenantId(), command.mandateId())
                .orElseThrow(() -> new NachMandateNotFoundException(command.mandateId()));

        if (!mandate.isActive()) {
            throw new IllegalStateException("NACH mandate is not active: " + command.mandateId() + " status=" + mandate.getStatus());
        }

        Money amount = Money.of(command.amount(), command.currency());
        PaymentOrder order = PaymentOrder.createNachCollection(
                command.tenantId(), command.loanId(), mandate.getCustomerId(),
                amount, mandate.getMandateId().toString(), mandate.getUmrn()
        );

        PaymentOrder saved = paymentOrderRepository.save(order);
        submitToRail(saved);

        outboxPublisher.publish("PaymentOrder", saved.getPaymentOrderId(),
                "originex.payments.CollectionInitiated", command.tenantId(),
                buildPaymentEventPayload(saved));

        return saved;
    }

    @Override
    public PaymentOrder recordInboundPayment(RecordInboundPaymentCommand command) {
        log.info("Recording inbound payment: loanId={}, amount={}", command.loanId(), command.amount());

        Money amount = Money.of(command.amount(), command.currency());
        PaymentRail rail;
        try {
            rail = PaymentRail.valueOf(command.paymentRail().toUpperCase());
        } catch (IllegalArgumentException e) {
            rail = PaymentRail.NEFT;
        }

        PaymentOrder order = PaymentOrder.createNachCollection(
                command.tenantId(), command.loanId(), command.customerId(),
                amount, null, null
        );

        // Mark immediately as completed since payment already received
        order.initiate();
        order.markProcessing();
        order.complete(command.externalTransactionId(), null);

        PaymentOrder saved = paymentOrderRepository.save(order);

        // Notify LMS via Kafka that payment was received
        outboxPublisher.publish("PaymentOrder", saved.getPaymentOrderId(),
                "originex.payments.PaymentReceived", command.tenantId(),
                buildPaymentReceivedPayload(saved, command.loanId()));

        log.info("Inbound payment recorded: paymentOrderId={}, amount={}", saved.getPaymentOrderId(), amount);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentOrder getPaymentOrder(UUID tenantId, UUID paymentOrderId) {
        return paymentOrderRepository.findById(tenantId, paymentOrderId)
                .orElseThrow(() -> new PaymentOrderNotFoundException(paymentOrderId));
    }

    @Override
    public PaymentOrder handlePaymentCallback(PaymentCallbackCommand command) {
        PaymentOrder order = paymentOrderRepository.findById(command.tenantId(), command.paymentOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Payment order not found: " + command.paymentOrderId()));

        switch (command.status().toUpperCase()) {
            case "SUCCESS" -> {
                order.complete(command.externalTransactionId(), command.bankReferenceNumber());
                log.info("Payment completed: paymentOrderId={}, utr={}", order.getPaymentOrderId(), command.externalTransactionId());

                // Determine event type from payment type
                String eventType = order.getPaymentType() == PaymentType.DISBURSEMENT
                        ? "originex.payments.DisbursementCompleted"
                        : "originex.payments.PaymentReceived";

                outboxPublisher.publish("PaymentOrder", order.getPaymentOrderId(), eventType,
                        command.tenantId(), buildPaymentReceivedPayload(order, order.getLoanId()));
            }
            case "FAILED" -> {
                order.fail(command.failureReason());
                log.warn("Payment failed: paymentOrderId={}, reason={}", order.getPaymentOrderId(), command.failureReason());

                // Schedule retry if retries remaining
                if (order.getRetryCount() < order.getMaxRetries()) {
                    order.scheduleRetry();
                }

                outboxPublisher.publish("PaymentOrder", order.getPaymentOrderId(),
                        "originex.payments.PaymentFailed", command.tenantId(),
                        buildPaymentEventPayload(order));
            }
            default -> log.debug("Payment callback status pending: paymentOrderId={}", order.getPaymentOrderId());
        }

        return paymentOrderRepository.save(order);
    }

    /**
     * Retry scheduler entry point — picks up RETRY_PENDING orders and resubmits.
     * Runs every 5 minutes.
     *
     * <p>This is a cross-tenant sweep, so it must run on the BYPASSRLS (system)
     * route when RLS is enabled. System context is entered here, *outside* the
     * transactional boundary of {@link #retryFailedPayments()}, because the
     * routing datasource picks its route when the transaction acquires its
     * connection (see dev/RLS_DESIGN.md §5, §7.2). This method is therefore
     * non-transactional ({@link Propagation#NOT_SUPPORTED}) and delegates through
     * the Spring proxy ({@code self}) so {@code retryFailedPayments()} opens its
     * transaction while system context is already set. {@code runAsSystem}
     * guarantees the context is cleared in a finally block even if a retry throws.
     */
    @Scheduled(fixedDelayString = "${originex.payment.retry-interval-ms:300000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retryFailedPaymentsJob() {
        SystemContextHolder.runAsSystem(self::retryFailedPayments);
    }

    /**
     * Resubmits RETRY_PENDING orders in a single transaction. Invoked by
     * {@link #retryFailedPaymentsJob()} within system context — never schedule or
     * call this directly, or it will run on the RLS-subject route with no tenant
     * bound.
     */
    @Transactional
    public void retryFailedPayments() {
        List<PaymentOrder> pending = paymentOrderRepository.findPendingRetries(50);
        if (pending.isEmpty()) return;

        log.info("Processing {} payment retries", pending.size());
        for (PaymentOrder order : pending) {
            try {
                submitToRail(order);
                paymentOrderRepository.save(order);
            } catch (Exception e) {
                log.error("Retry failed for paymentOrderId={}: {}", order.getPaymentOrderId(), e.getMessage());
            }
        }
    }

    // ─── Private helpers ───

    private void submitToRail(PaymentOrder order) {
        PaymentRailPort railAdapter = railAdapters.get(order.getPaymentRail());
        if (railAdapter == null) {
            log.warn("No rail adapter found for {}, using sandbox fallback", order.getPaymentRail());
            order.initiate();
            order.markProcessing();
            return;
        }

        order.initiate();
        PaymentRailPort.PaymentSubmissionResult result = railAdapter.submit(order);

        if (result.accepted()) {
            order.markProcessing();
            if (result.externalTransactionId() != null) {
                // Synchronous rails (sandbox) complete immediately
                order.complete(result.externalTransactionId(), result.bankReferenceNumber());
            }
        } else {
            order.fail(result.failureReason());
        }
    }

    /**
     * Auto-selects a payment rail by amount when no explicit {@code preferred}
     * rail is given. An explicit preferred rail always wins over auto-selection,
     * regardless of amount.
     *
     * <p>Business rule (three non-overlapping bands, no gap):
     * <ul>
     *   <li>amount &gt; ₹5,00,000 → RTGS — high-value, no upper cap</li>
     *   <li>₹2,00,000 ≤ amount ≤ ₹5,00,000 → IMPS — instant 24x7, capped at ₹5L</li>
     *   <li>amount &lt; ₹2,00,000 → NEFT — no minimum, batched settlement</li>
     * </ul>
     */
    // Package-private (not private) so PaymentApplicationServiceTest can call it
    // directly instead of reconstructing behavior through the full
    // initiateDisbursement() flow, which would need repository/adapter mocks
    // that this pure amount-to-rail decision doesn't otherwise need.
    PaymentRail selectRail(String preferred, Money amount) {
        if (preferred != null && !preferred.isBlank()) {
            try { return PaymentRail.valueOf(preferred.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        BigDecimal amountValue = amount.getAmount();
        if (amountValue.compareTo(IMPS_MAX_AMOUNT) > 0) return PaymentRail.RTGS;
        if (amountValue.compareTo(IMPS_MIN_AMOUNT) >= 0) return PaymentRail.IMPS;
        return PaymentRail.NEFT;
    }

    private byte[] buildPaymentEventPayload(PaymentOrder order) {
        return String.format("{\"payment_order_id\":\"%s\",\"loan_id\":\"%s\",\"amount\":\"%s\"," +
                        "\"currency\":\"%s\",\"status\":\"%s\",\"rail\":\"%s\",\"reference\":\"%s\"}",
                order.getPaymentOrderId(), order.getLoanId(),
                order.getAmount().getAmount().toPlainString(), order.getAmount().getCurrencyCode(),
                order.getStatus(), order.getPaymentRail(), order.getPaymentReference()
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildPaymentReceivedPayload(PaymentOrder order, UUID loanId) {
        return String.format("{\"payment_order_id\":\"%s\",\"loan_id\":\"%s\",\"amount\":\"%s\"," +
                        "\"currency\":\"%s\",\"utr\":\"%s\",\"payment_type\":\"%s\"}",
                order.getPaymentOrderId(), loanId,
                order.getAmount().getAmount().toPlainString(), order.getAmount().getCurrencyCode(),
                order.getExternalTransactionId() != null ? order.getExternalTransactionId() : "",
                order.getPaymentType()
        ).getBytes(StandardCharsets.UTF_8);
    }
}
