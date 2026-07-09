package com.originex.customer.application.port.out;

/**
 * Outbound port — Aadhaar e-KYC verification via the Partner Integration Service
 * (Anti-Corruption Layer). Customer Service never calls DigiLocker/UIDAI directly,
 * and the raw Aadhaar number never persists beyond this call's scope.
 */
public interface AadhaarVerificationPort {

    AadhaarVerificationResult verify(AadhaarVerificationRequest request);

    record AadhaarVerificationRequest(
            String tenantId,
            String referenceId,       // customerId
            String aadhaarNumberOrVid,
            String consentArtifactId,
            String otpReference
    ) {}

    record AadhaarVerificationResult(
            boolean verified,
            String maskedAadhaar,
            String nameOnRecord,
            String dobOnRecord,
            String verificationReference,
            String failureReason
    ) {}
}
