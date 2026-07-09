package com.originex.partner.application.port.out;

import com.originex.partner.domain.model.AadhaarVerificationResult;

/**
 * Anti-Corruption Layer port — Aadhaar e-KYC (via DigiLocker or UIDAI-licensed AUA/KUA).
 */
public interface AadhaarVerificationPort {

    AadhaarVerificationResult verify(AadhaarVerificationRequest request);

    record AadhaarVerificationRequest(
            String aadhaarNumberOrVid,   // Never persisted — used only for the live call
            String consentArtifactId,
            String otpReference          // If OTP-based eKYC flow
    ) {}
}
