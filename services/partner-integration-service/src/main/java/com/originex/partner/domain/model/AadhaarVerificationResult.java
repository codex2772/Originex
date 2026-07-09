package com.originex.partner.domain.model;

/**
 * Result of Aadhaar e-KYC verification (via DigiLocker / UIDAI-licensed AUA-KUA).
 * Aadhaar number itself is NEVER returned or stored — only a masked reference
 * and the demographic data needed for name/DOB match (DPDPA compliance).
 */
public record AadhaarVerificationResult(
        boolean verified,
        String maskedAadhaar,       // e.g., "XXXXXXXX1234"
        String nameOnRecord,
        String dobOnRecord,
        String gender,
        String addressLine,
        String photoBase64,         // Returned by UIDAI for face-match, not persisted long-term
        String verificationReference,
        String failureReason
) {
    public static AadhaarVerificationResult failed(String reason) {
        return new AadhaarVerificationResult(false, null, null, null, null, null, null, null, reason);
    }
}
