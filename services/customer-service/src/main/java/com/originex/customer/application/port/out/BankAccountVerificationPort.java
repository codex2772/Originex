package com.originex.customer.application.port.out;

/**
 * Outbound port — Bank account verification (penny-drop) via the
 * Partner Integration Service.
 */
public interface BankAccountVerificationPort {

    BankAccountVerificationResult verify(BankAccountVerificationRequest request);

    record BankAccountVerificationRequest(
            String tenantId,
            String referenceId,   // bankAccountId
            String accountNumber,
            String ifscCode,
            String expectedAccountHolderName
    ) {}

    record BankAccountVerificationResult(
            boolean verified,
            String bankName,
            boolean nameMatch,
            String failureReason
    ) {}
}
