package com.originex.customer.adapter.out.persistence;

import com.originex.customer.domain.model.KycRecord;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_records")
public class KycRecordJpaEntity {

    @Id
    @Column(name = "kyc_record_id")
    private UUID kycRecordId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerJpaEntity customer;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "kyc_type", nullable = false)
    private String kycType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "verification_reference")
    private String verificationReference;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public static KycRecordJpaEntity fromDomain(KycRecord domain, CustomerJpaEntity customer) {
        KycRecordJpaEntity e = new KycRecordJpaEntity();
        e.kycRecordId = domain.getKycRecordId();
        e.customer = customer;
        e.tenantId = customer.getTenantId();
        e.kycType = domain.getKycType().name();
        e.status = domain.getStatus().name();
        e.verificationReference = domain.getVerificationReference();
        e.rejectionReason = domain.getRejectionReason();
        e.submittedAt = domain.getSubmittedAt();
        e.verifiedAt = domain.getVerifiedAt();
        e.expiresAt = domain.getExpiresAt();
        return e;
    }

    public KycRecord toDomain() {
        KycRecord r = new KycRecord();
        r.setKycRecordId(kycRecordId);
        r.setKycType(KycRecord.KycType.valueOf(kycType));
        r.setStatus(KycRecord.KycRecordStatus.valueOf(status));
        r.setVerificationReference(verificationReference);
        r.setRejectionReason(rejectionReason);
        r.setSubmittedAt(submittedAt);
        r.setVerifiedAt(verifiedAt);
        r.setExpiresAt(expiresAt);
        return r;
    }

    protected KycRecordJpaEntity() {}
}
