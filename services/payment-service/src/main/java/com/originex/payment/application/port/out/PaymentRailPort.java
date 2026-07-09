package com.originex.payment.application.port.out;

import com.originex.payment.domain.model.PaymentOrder;
import com.originex.payment.domain.model.PaymentOrder.PaymentRail;

/**
 * Anti-Corruption Layer port — actual payment rail execution.
 * Concrete adapters talk to NEFT/RTGS/IMPS/UPI APIs.
 * The domain never knows which rail processed the payment.
 */
public interface PaymentRailPort {

    /** Which rail this adapter handles */
    PaymentRail rail();

    /**
     * Submit the payment to the external rail.
     * Returns immediately — actual confirmation comes via webhook callback.
     */
    PaymentSubmissionResult submit(PaymentOrder order);

    /**
     * Query payment status from rail (used for reconciliation).
     */
    PaymentStatusResult query(String paymentReference);

    record PaymentSubmissionResult(
            boolean accepted,
            String externalTransactionId,
            String bankReferenceNumber,
            String failureReason
    ) {}

    record PaymentStatusResult(
            String status,            // PENDING, SUCCESS, FAILED
            String externalTransactionId,
            String bankReferenceNumber,
            String failureReason
    ) {}
}
