package com.originex.los.adapter.out.persistence;

import com.originex.los.domain.model.ApplicationDocument;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "application_documents")
public class ApplicationDocumentJpaEntity {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private LoanApplicationJpaEntity application;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "storage_url", nullable = false)
    private String storageUrl;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    public static ApplicationDocumentJpaEntity fromDomain(ApplicationDocument domain, LoanApplicationJpaEntity app) {
        ApplicationDocumentJpaEntity e = new ApplicationDocumentJpaEntity();
        e.documentId = domain.getDocumentId();
        e.application = app;
        e.tenantId = app.getTenantId();
        e.documentType = domain.getType().name();
        e.status = domain.getStatus().name();
        e.fileName = domain.getFileName();
        e.storageUrl = domain.getStorageUrl();
        e.rejectionReason = domain.getRejectionReason();
        e.uploadedAt = domain.getUploadedAt();
        e.verifiedAt = domain.getVerifiedAt();
        return e;
    }

    public ApplicationDocument toDomain() {
        return ApplicationDocument.upload(
                ApplicationDocument.DocumentType.valueOf(documentType),
                fileName, storageUrl
        );
    }

    protected ApplicationDocumentJpaEntity() {}
}
