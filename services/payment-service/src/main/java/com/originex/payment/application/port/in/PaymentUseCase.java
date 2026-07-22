package com.originex.payment.application.port.in;

import com.originex.payment.domain.model.NachMandate;
import com.originex.payment.domain.model.PaymentOrder;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

/**
 * Authorization declared on the use-case port (single enforcement point), inert while
 * {@code originex.security.enabled=false}. Scope model (privilege boundaries):
 * <ul>
 *   <li>{@code payments:read} — reads.</li>
 *   <li><b>{@code payments:disburse}</b> — money-out; <b>dual-called</b>: the HTTP endpoint enforces it,
 *       and the LoanDisbursed consumer holds exactly this as its minimal machine grant (see
 *       {@code MachineActorContext}).</li>
 *   <li>{@code payments:initiate} — collection / inbound / mandate-setup (money-in and setup side).</li>
 *   <li><b>{@code payments:callback}</b> — the gateway webhook; <b>machine-only</b> (gateway
 *       service-account), no human persona (see {@link OriginexScopes#PAYMENTS_CALLBACK}, KI-14).</li>
 * </ul>
 */
public interface PaymentUseCase {

    String REQUIRES_PAYMENTS_READ =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.PAYMENTS_READ + "')";
    String REQUIRES_PAYMENTS_INITIATE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.PAYMENTS_INITIATE + "')";
    String REQUIRES_PAYMENTS_DISBURSE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.PAYMENTS_DISBURSE + "')";
    String REQUIRES_PAYMENTS_CALLBACK =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.PAYMENTS_CALLBACK + "')";

    /** Initiate a disbursement — called when LMS confirms a loan is disbursed */
    @PreAuthorize(REQUIRES_PAYMENTS_DISBURSE)
    PaymentOrder initiateDisbursement(InitiateDisbursementCommand command);

    /** Register a NACH mandate for a loan */
    @PreAuthorize(REQUIRES_PAYMENTS_INITIATE)
    NachMandate registerNachMandate(RegisterMandateCommand command);

    /** Trigger NACH debit for EMI collection */
    @PreAuthorize(REQUIRES_PAYMENTS_INITIATE)
    PaymentOrder triggerNachCollection(TriggerCollectionCommand command);

    /** Record an inbound payment (UPI/NEFT from borrower manually) */
    @PreAuthorize(REQUIRES_PAYMENTS_INITIATE)
    PaymentOrder recordInboundPayment(RecordInboundPaymentCommand command);

    /** Get a payment order by ID */
    @PreAuthorize(REQUIRES_PAYMENTS_READ)
    PaymentOrder getPaymentOrder(UUID tenantId, UUID paymentOrderId);

    /** Webhook callback from payment rail — updates status (machine-only: gateway service account) */
    @PreAuthorize(REQUIRES_PAYMENTS_CALLBACK)
    PaymentOrder handlePaymentCallback(PaymentCallbackCommand command);

    // ─── Commands ───

    record InitiateDisbursementCommand(
            UUID tenantId,
            UUID loanId,
            UUID customerId,
            String amount,
            String currency,
            String beneficiaryAccountNumber,
            String beneficiaryIfsc,
            String beneficiaryName,
            String beneficiaryBankName,
            String preferredRail        // NEFT, RTGS, IMPS — null = auto-select
    ) {}

    record RegisterMandateCommand(
            UUID tenantId,
            UUID loanId,
            UUID customerId,
            String bankAccountNumber,
            String ifscCode,
            String bankName,
            String accountHolderName,
            String maxDebitAmount,
            String currency,
            String startDate,
            String endDate
    ) {}

    record TriggerCollectionCommand(
            UUID tenantId,
            UUID loanId,
            UUID mandateId,
            String amount,
            String currency,
            String installmentReference
    ) {}

    record RecordInboundPaymentCommand(
            UUID tenantId,
            UUID loanId,
            UUID customerId,
            String amount,
            String currency,
            String paymentRail,
            String externalTransactionId,
            String narration
    ) {}

    record PaymentCallbackCommand(
            UUID tenantId,
            UUID paymentOrderId,
            String status,              // SUCCESS, FAILED, PENDING
            String externalTransactionId,
            String bankReferenceNumber,
            String failureReason
    ) {}
}
