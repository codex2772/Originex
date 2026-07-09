package com.originex.partner.domain.model;

/**
 * Result of bank account verification via penny-drop / NPCI Vayu
 * (Karza / Decentro / Cashfree / Signzy style APIs).
 */
public record BankAccountVerificationResult(
        boolean verified,
        String accountNumberMasked,
        String ifscCode,
        String bankName,
        String branchName,
        String nameOnAccount,
        boolean nameMatch,
        String accountStatus,       // ACTIVE, INACTIVE, INVALID
        String utrReference,        // The penny-drop transaction reference
        String failureReason
) {
    public static BankAccountVerificationResult failed(String reason) {
        return new BankAccountVerificationResult(false, null, null, null, null, null, false, "INVALID", null, reason);
    }
}
