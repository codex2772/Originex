package com.originex.partner.application.port.out;

import com.originex.partner.domain.model.BankAccountVerificationResult;

/**
 * Anti-Corruption Layer port — Bank Account verification (penny-drop / NPCI Vayu).
 */
public interface BankAccountVerificationPort {

    BankAccountVerificationResult verify(BankAccountVerificationRequest request);

    record BankAccountVerificationRequest(
            String accountNumber,
            String ifscCode,
            String expectedAccountHolderName
    ) {}
}
