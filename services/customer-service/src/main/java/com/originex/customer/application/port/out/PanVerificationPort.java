package com.originex.customer.application.port.out;

/**
 * Outbound port — PAN verification via the Partner Integration Service
 * (Anti-Corruption Layer). Customer Service never calls NSDL directly.
 */
public interface PanVerificationPort {

    PanVerificationResult verify(PanVerificationRequest request);

    record PanVerificationRequest(
            String tenantId,
            String referenceId,   // customerId
            String panNumber,
            String fullName,
            String dateOfBirth
    ) {}

    record PanVerificationResult(
            boolean valid,
            String nameOnRecord,
            String panStatus,
            boolean nameMatch,
            String failureReason
    ) {}
}
