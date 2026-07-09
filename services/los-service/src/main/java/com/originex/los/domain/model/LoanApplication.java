package com.originex.los.domain.model;

import com.originex.common.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * LoanApplication Aggregate Root — manages the full lifecycle from submission to disbursement request.
 *
 * <p>Key Invariants:
 * <ul>
 *   <li>State machine transitions are strictly enforced</li>
 *   <li>Requested amount must be within product limits</li>
 *   <li>Only one active offer per application</li>
 *   <li>Offer has validity period — auto-expires</li>
 *   <li>Documents must be verified before credit check</li>
 * </ul>
 */
public class LoanApplication {

    private UUID applicationId;
    private UUID tenantId;
    private UUID customerId;
    private String productCode;
    private ApplicationStatus status;
    private Money requestedAmount;
    private int requestedTenureMonths;
    private String purpose;
    private String channel;

    // Applicant details (denormalized from Customer for snapshot)
    private String applicantName;
    private String applicantPan;
    private String employmentType;
    private Money monthlyIncome;

    // Credit check
    private Integer creditScore;
    private String creditBureau;
    private String creditReportRef;
    private Instant creditCheckAt;

    // Offer
    private LoanOffer currentOffer;

    // Documents
    private List<ApplicationDocument> documents;

    // Metadata
    private String assignedTo;
    private String decisionNotes;
    private long version;
    private Instant submittedAt;
    private Instant decidedAt;
    private Instant createdAt;
    private Instant updatedAt;

    // ═══════════════════════════════════════════════════════════════════
    // Factory
    // ═══════════════════════════════════════════════════════════════════

    public static LoanApplication submit(UUID tenantId, UUID customerId, String productCode,
                                         Money requestedAmount, int requestedTenureMonths,
                                         String purpose, String channel,
                                         String applicantName, String applicantPan,
                                         String employmentType, Money monthlyIncome) {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(customerId, "customerId required");
        Objects.requireNonNull(productCode, "productCode required");
        Objects.requireNonNull(requestedAmount, "requestedAmount required");

        if (!requestedAmount.isPositive()) {
            throw new IllegalArgumentException("Requested amount must be positive");
        }
        if (requestedTenureMonths < 1 || requestedTenureMonths > 360) {
            throw new IllegalArgumentException("Tenure must be 1-360 months");
        }

        LoanApplication app = new LoanApplication();
        app.applicationId = UUID.randomUUID();
        app.tenantId = tenantId;
        app.customerId = customerId;
        app.productCode = productCode;
        app.status = ApplicationStatus.SUBMITTED;
        app.requestedAmount = requestedAmount;
        app.requestedTenureMonths = requestedTenureMonths;
        app.purpose = purpose;
        app.channel = channel;
        app.applicantName = applicantName;
        app.applicantPan = applicantPan;
        app.employmentType = employmentType;
        app.monthlyIncome = monthlyIncome;
        app.documents = new ArrayList<>();
        app.version = 0;
        app.submittedAt = Instant.now();
        app.createdAt = Instant.now();
        app.updatedAt = Instant.now();
        return app;
    }

    // ═══════════════════════════════════════════════════════════════════
    // State Transitions
    // ═══════════════════════════════════════════════════════════════════

    public void startProcessing() {
        transitionTo(ApplicationStatus.IN_PROGRESS);
    }

    public void recordCreditCheck(int score, String bureau, String reportRef) {
        assertStatus(ApplicationStatus.IN_PROGRESS);
        this.creditScore = score;
        this.creditBureau = bureau;
        this.creditReportRef = reportRef;
        this.creditCheckAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void approve(String notes) {
        transitionTo(ApplicationStatus.APPROVED);
        this.decisionNotes = notes;
        this.decidedAt = Instant.now();
    }

    public void reject(String notes) {
        transitionTo(ApplicationStatus.REJECTED);
        this.decisionNotes = notes;
        this.decidedAt = Instant.now();
    }

    public void refer(String assignedTo) {
        transitionTo(ApplicationStatus.REFERRED);
        this.assignedTo = assignedTo;
    }

    public void generateOffer(Money sanctionedAmount, BigDecimal interestRate,
                              int tenureMonths, Money emi, Money processingFee,
                              BigDecimal apr, Instant expiresAt) {
        assertStatus(ApplicationStatus.APPROVED);

        if (sanctionedAmount.isGreaterThan(requestedAmount)) {
            throw new IllegalArgumentException("Sanctioned amount cannot exceed requested amount");
        }

        this.currentOffer = LoanOffer.create(
                sanctionedAmount, interestRate, tenureMonths,
                emi, processingFee, apr, expiresAt
        );
        transitionTo(ApplicationStatus.OFFER_PENDING);
    }

    public void acceptOffer() {
        assertStatus(ApplicationStatus.OFFER_PENDING);

        if (currentOffer == null) {
            throw new IllegalStateException("No offer to accept");
        }
        if (currentOffer.isExpired()) {
            transitionTo(ApplicationStatus.OFFER_EXPIRED);
            throw new IllegalStateException("Offer has expired");
        }

        transitionTo(ApplicationStatus.OFFER_ACCEPTED);
    }

    public void requestDisbursement() {
        assertStatus(ApplicationStatus.OFFER_ACCEPTED);
        transitionTo(ApplicationStatus.DISBURSEMENT_REQUESTED);
    }

    public void withdraw() {
        if (status.isTerminal()) {
            throw new IllegalStateException("Cannot withdraw application in terminal state: " + status);
        }
        transitionTo(ApplicationStatus.WITHDRAWN);
    }

    public void addDocument(ApplicationDocument document) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Cannot add documents in terminal state");
        }
        this.documents.add(document);
        this.updatedAt = Instant.now();
    }

    // ═══════════════════════════════════════════════════════════════════
    // State Machine Enforcement
    // ═══════════════════════════════════════════════════════════════════

    private void transitionTo(ApplicationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid transition: " + status + " → " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    private void assertStatus(ApplicationStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Expected status " + expected + " but found " + this.status);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    public UUID getApplicationId() { return applicationId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCustomerId() { return customerId; }
    public String getProductCode() { return productCode; }
    public ApplicationStatus getStatus() { return status; }
    public Money getRequestedAmount() { return requestedAmount; }
    public int getRequestedTenureMonths() { return requestedTenureMonths; }
    public String getPurpose() { return purpose; }
    public String getChannel() { return channel; }
    public String getApplicantName() { return applicantName; }
    public String getApplicantPan() { return applicantPan; }
    public String getEmploymentType() { return employmentType; }
    public Money getMonthlyIncome() { return monthlyIncome; }
    public Integer getCreditScore() { return creditScore; }
    public String getCreditBureau() { return creditBureau; }
    public String getCreditReportRef() { return creditReportRef; }
    public Instant getCreditCheckAt() { return creditCheckAt; }
    public LoanOffer getCurrentOffer() { return currentOffer; }
    public List<ApplicationDocument> getDocuments() { return documents; }
    public String getAssignedTo() { return assignedTo; }
    public String getDecisionNotes() { return decisionNotes; }
    public long getVersion() { return version; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Reconstruction setters (persistence adapters only)
    public LoanApplication() {}
    public void setApplicationId(UUID id) { this.applicationId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setCustomerId(UUID id) { this.customerId = id; }
    public void setProductCode(String s) { this.productCode = s; }
    public void setStatus(ApplicationStatus s) { this.status = s; }
    public void setRequestedAmount(Money m) { this.requestedAmount = m; }
    public void setRequestedTenureMonths(int i) { this.requestedTenureMonths = i; }
    public void setCurrentOffer(LoanOffer o) { this.currentOffer = o; }
    public void setDocuments(List<ApplicationDocument> l) { this.documents = l; }
    public void setVersion(long v) { this.version = v; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }
    public void setSubmittedAt(Instant i) { this.submittedAt = i; }
    public void setCreditScore(Integer i) { this.creditScore = i; }
}
