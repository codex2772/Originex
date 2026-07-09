package com.originex.los.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.los.domain.model.ApplicationDocument;
import com.originex.los.domain.model.ApplicationStatus;
import com.originex.los.domain.model.LoanApplication;
import com.originex.los.domain.model.LoanOffer;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "loan_applications")
public class LoanApplicationJpaEntity {

    @Id
    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApplicationStatus status;

    @Column(name = "requested_amount", nullable = false)
    private BigDecimal requestedAmount;

    @Column(name = "requested_currency", nullable = false)
    private String requestedCurrency;

    @Column(name = "requested_tenure", nullable = false)
    private int requestedTenure;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "channel")
    private String channel;

    @Column(name = "applicant_name")
    private String applicantName;

    @Column(name = "applicant_pan")
    private String applicantPan;

    @Column(name = "employment_type")
    private String employmentType;

    @Column(name = "monthly_income")
    private BigDecimal monthlyIncome;

    @Column(name = "monthly_income_currency")
    private String monthlyIncomeCurrency;

    @Column(name = "credit_score")
    private Integer creditScore;

    @Column(name = "credit_bureau")
    private String creditBureau;

    @Column(name = "credit_report_ref")
    private String creditReportRef;

    @Column(name = "credit_check_at")
    private Instant creditCheckAt;

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "decision_notes")
    private String decisionNotes;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToOne(mappedBy = "application", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private LoanOfferJpaEntity offer;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ApplicationDocumentJpaEntity> documents = new ArrayList<>();

    // ─── Domain ↔ JPA ───

    public static LoanApplicationJpaEntity fromDomain(LoanApplication domain) {
        LoanApplicationJpaEntity e = new LoanApplicationJpaEntity();
        e.applicationId = domain.getApplicationId();
        e.tenantId = domain.getTenantId();
        e.customerId = domain.getCustomerId();
        e.productCode = domain.getProductCode();
        e.status = domain.getStatus();
        e.requestedAmount = domain.getRequestedAmount().getAmount();
        e.requestedCurrency = domain.getRequestedAmount().getCurrencyCode();
        e.requestedTenure = domain.getRequestedTenureMonths();
        e.purpose = domain.getPurpose();
        e.channel = domain.getChannel();
        e.applicantName = domain.getApplicantName();
        e.applicantPan = domain.getApplicantPan();
        e.employmentType = domain.getEmploymentType();
        if (domain.getMonthlyIncome() != null) {
            e.monthlyIncome = domain.getMonthlyIncome().getAmount();
            e.monthlyIncomeCurrency = domain.getMonthlyIncome().getCurrencyCode();
        }
        e.creditScore = domain.getCreditScore();
        e.creditBureau = domain.getCreditBureau();
        e.creditReportRef = domain.getCreditReportRef();
        e.creditCheckAt = domain.getCreditCheckAt();
        e.assignedTo = domain.getAssignedTo();
        e.decisionNotes = domain.getDecisionNotes();
        e.version = domain.getVersion();
        e.submittedAt = domain.getSubmittedAt();
        e.decidedAt = domain.getDecidedAt();
        e.createdAt = domain.getCreatedAt();
        e.updatedAt = domain.getUpdatedAt();

        if (domain.getCurrentOffer() != null) {
            e.offer = LoanOfferJpaEntity.fromDomain(domain.getCurrentOffer(), e);
        }

        domain.getDocuments().forEach(d -> {
            e.documents.add(ApplicationDocumentJpaEntity.fromDomain(d, e));
        });

        return e;
    }

    public LoanApplication toDomain() {
        LoanApplication app = new LoanApplication();
        app.setApplicationId(applicationId);
        app.setTenantId(tenantId);
        app.setCustomerId(customerId);
        app.setProductCode(productCode);
        app.setStatus(status);
        app.setRequestedAmount(Money.of(requestedAmount, requestedCurrency));
        app.setRequestedTenureMonths(requestedTenure);
        app.setCreditScore(creditScore);
        app.setVersion(version);
        app.setSubmittedAt(submittedAt);
        app.setCreatedAt(createdAt);
        app.setUpdatedAt(updatedAt);

        if (offer != null) {
            app.setCurrentOffer(offer.toDomain());
        }

        List<ApplicationDocument> docList = documents.stream()
                .map(ApplicationDocumentJpaEntity::toDomain).toList();
        app.setDocuments(new ArrayList<>(docList));

        return app;
    }

    public UUID getApplicationId() { return applicationId; }
    public UUID getTenantId() { return tenantId; }

    protected LoanApplicationJpaEntity() {}
}
