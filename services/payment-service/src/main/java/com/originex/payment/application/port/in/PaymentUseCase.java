package com.originex.payment.application.port.in;

import com.originex.payment.domain.model.NachMandate;
import com.originex.payment.domain.model.PaymentOrder;

import java.util.UUID;

public interface PaymentUseCase {

    /** Initiate a disbursement — called when LMS confirms a loan is disbursed */
    PaymentOrder initiateDisbursement(InitiateDisbursementCommand command);

    /** Register a NACH mandate for a loan */
    NachMandate registerNachMandate(RegisterMandateCommand command);

    /** Trigger NACH debit for EMI collection */
    PaymentOrder triggerNachCollection(TriggerCollectionCommand command);

    /** Record an inbound payment (UPI/NEFT from borrower manually) */
    PaymentOrder recordInboundPayment(RecordInboundPaymentCommand command);

    /** Get a payment order by ID */
    PaymentOrder getPaymentOrder(UUID tenantId, UUID paymentOrderId);

    /** Webhook callback from payment rail — updates status */
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
