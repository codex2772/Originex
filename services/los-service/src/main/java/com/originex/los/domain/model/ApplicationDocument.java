package com.originex.los.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Document attached to a loan application.
 */
public class ApplicationDocument {

    private UUID documentId;
    private DocumentType type;
    private DocumentStatus status;
    private String fileName;
    private String storageUrl;
    private String rejectionReason;
    private Instant uploadedAt;
    private Instant verifiedAt;

    public static ApplicationDocument upload(DocumentType type, String fileName, String storageUrl) {
        ApplicationDocument doc = new ApplicationDocument();
        doc.documentId = UUID.randomUUID();
        doc.type = type;
        doc.fileName = fileName;
        doc.storageUrl = storageUrl;
        doc.status = DocumentStatus.PENDING;
        doc.uploadedAt = Instant.now();
        return doc;
    }

    public void verify() {
        if (this.status != DocumentStatus.PENDING) {
            throw new IllegalStateException("Document not in PENDING state");
        }
        this.status = DocumentStatus.VERIFIED;
        this.verifiedAt = Instant.now();
    }

    public void reject(String reason) {
        this.status = DocumentStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public UUID getDocumentId() { return documentId; }
    public DocumentType getType() { return type; }
    public DocumentStatus getStatus() { return status; }
    public String getFileName() { return fileName; }
    public String getStorageUrl() { return storageUrl; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }

    public ApplicationDocument() {}

    public enum DocumentType {
        PAN_CARD, AADHAAR, SALARY_SLIP, BANK_STATEMENT,
        ITR, ADDRESS_PROOF, PHOTO, EMPLOYMENT_LETTER
    }

    public enum DocumentStatus { PENDING, VERIFIED, REJECTED }
}
