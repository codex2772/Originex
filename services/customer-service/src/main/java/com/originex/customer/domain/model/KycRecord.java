package com.originex.customer.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * KYC verification record — tracks verification history for audit.
 */
public class KycRecord {

    private UUID kycRecordId;
    private KycType kycType;
    private KycRecordStatus status;
    private String verificationReference;
    private String rejectionReason;
    private Instant submittedAt;
    private Instant verifiedAt;
    private Instant expiresAt;

    public static KycRecord create(KycType type, String verificationReference) {
        KycRecord record = new KycRecord();
        record.kycRecordId = UUID.randomUUID();
        record.kycType = type;
        record.status = KycRecordStatus.PENDING;
        record.verificationReference = verificationReference;
        record.submittedAt = Instant.now();
        return record;
    }

    public void markVerified() {
        this.status = KycRecordStatus.VERIFIED;
        this.verifiedAt = Instant.now();
        // KYC valid for 2 years
        this.expiresAt = Instant.now().plusSeconds(2L * 365 * 24 * 60 * 60);
    }

    public void markRejected(String reason) {
        this.status = KycRecordStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    // Accessors
    public UUID getKycRecordId() { return kycRecordId; }
    public KycType getKycType() { return kycType; }
    public KycRecordStatus getStatus() { return status; }
    public String getVerificationReference() { return verificationReference; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public KycRecord() {}
    public void setKycRecordId(UUID id) { this.kycRecordId = id; }
    public void setKycType(KycType t) { this.kycType = t; }
    public void setStatus(KycRecordStatus s) { this.status = s; }
    public void setVerificationReference(String s) { this.verificationReference = s; }
    public void setRejectionReason(String s) { this.rejectionReason = s; }
    public void setSubmittedAt(Instant i) { this.submittedAt = i; }
    public void setVerifiedAt(Instant i) { this.verifiedAt = i; }
    public void setExpiresAt(Instant i) { this.expiresAt = i; }

    public enum KycType { VIDEO_KYC, EKYC_AADHAAR, IN_PERSON, DIGILOCKER }
    public enum KycRecordStatus { PENDING, VERIFIED, REJECTED, EXPIRED }
}
