package com.originex.lms.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.lms.domain.model.*;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "loans")
public class LoanJpaEntity {

    @Id
    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "loan_account_number", nullable = false)
    private String loanAccountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LoanStatus status;

    @Column(name = "sanctioned_amount", nullable = false)
    private BigDecimal sanctionedAmount;

    @Column(name = "disbursed_amount", nullable = false)
    private BigDecimal disbursedAmount;

    @Column(name = "outstanding_principal", nullable = false)
    private BigDecimal outstandingPrincipal;

    @Column(name = "outstanding_interest", nullable = false)
    private BigDecimal outstandingInterest;

    @Column(name = "outstanding_charges", nullable = false)
    private BigDecimal outstandingCharges;

    @Column(name = "interest_rate", nullable = false)
    private BigDecimal interestRate;

    @Column(name = "rate_type", nullable = false)
    private String rateType;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(name = "remaining_tenure", nullable = false)
    private int remainingTenure;

    @Column(name = "emi_amount", nullable = false)
    private BigDecimal emiAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "sanction_date", nullable = false)
    private LocalDate sanctionDate;

    @Column(name = "first_disbursement_date")
    private LocalDate firstDisbursementDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "last_payment_date")
    private LocalDate lastPaymentDate;

    @Column(name = "last_accrual_date")
    private LocalDate lastAccrualDate;

    @Column(name = "dpd", nullable = false)
    private int dpd;

    @Column(name = "max_dpd", nullable = false)
    private int maxDpd;

    @Column(name = "asset_classification", nullable = false)
    private String assetClassification;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("installmentNumber ASC")
    private List<InstallmentJpaEntity> installments = new ArrayList<>();

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DisbursementJpaEntity> disbursements = new ArrayList<>();

    // ─── Domain ↔ JPA ───

    public static LoanJpaEntity fromDomain(Loan loan) {
        LoanJpaEntity e = new LoanJpaEntity();
        e.loanId = loan.getLoanId();
        e.tenantId = loan.getTenantId();
        e.customerId = loan.getCustomerId();
        e.applicationId = loan.getApplicationId();
        e.productCode = loan.getProductCode();
        e.loanAccountNumber = loan.getLoanAccountNumber();
        e.status = loan.getStatus();
        e.sanctionedAmount = loan.getSanctionedAmount().getAmount();
        e.disbursedAmount = loan.getDisbursedAmount().getAmount();
        e.outstandingPrincipal = loan.getOutstandingPrincipal().getAmount();
        e.outstandingInterest = loan.getOutstandingInterest().getAmount();
        e.outstandingCharges = loan.getOutstandingCharges().getAmount();
        e.interestRate = loan.getInterestRate();
        e.rateType = loan.getRateType();
        e.tenureMonths = loan.getTenureMonths();
        e.remainingTenure = loan.getRemainingTenure();
        e.emiAmount = loan.getEmiAmount().getAmount();
        e.currency = loan.getCurrency();
        e.sanctionDate = loan.getSanctionedAmount() != null ? LocalDate.now() : null;
        e.maturityDate = loan.getMaturityDate();
        e.nextDueDate = loan.getNextDueDate();
        e.lastPaymentDate = loan.getLastPaymentDate();
        e.lastAccrualDate = loan.getLastAccrualDate();
        e.dpd = loan.getDpd();
        e.maxDpd = loan.getMaxDpd();
        e.assetClassification = loan.getAssetClassification();
        e.version = loan.getVersion();
        e.createdAt = loan.getCreatedAt();
        e.updatedAt = loan.getUpdatedAt();

        loan.getInstallments().forEach(inst -> {
            e.installments.add(InstallmentJpaEntity.fromDomain(inst, e));
        });

        loan.getDisbursements().forEach(d -> {
            e.disbursements.add(DisbursementJpaEntity.fromDomain(d, e));
        });

        return e;
    }

    public Loan toDomain() {
        Loan loan = new Loan();
        loan.setLoanId(loanId);
        loan.setTenantId(tenantId);
        loan.setCustomerId(customerId);
        loan.setApplicationId(applicationId);
        loan.setProductCode(productCode);
        loan.setLoanAccountNumber(loanAccountNumber);
        loan.setStatus(status);
        loan.setSanctionedAmount(Money.of(sanctionedAmount, currency));
        loan.setDisbursedAmount(Money.of(disbursedAmount, currency));
        loan.setOutstandingPrincipal(Money.of(outstandingPrincipal, currency));
        loan.setOutstandingInterest(Money.of(outstandingInterest, currency));
        loan.setOutstandingCharges(Money.of(outstandingCharges, currency));
        loan.setInterestRate(interestRate);
        loan.setRateType(rateType);
        loan.setTenureMonths(tenureMonths);
        loan.setRemainingTenure(remainingTenure);
        loan.setEmiAmount(Money.of(emiAmount, currency));
        loan.setCurrency(currency);
        loan.setMaturityDate(maturityDate);
        loan.setNextDueDate(nextDueDate);
        loan.setLastAccrualDate(lastAccrualDate);
        loan.setDpd(dpd);
        loan.setMaxDpd(maxDpd);
        loan.setAssetClassification(assetClassification);
        loan.setVersion(version);
        loan.setCreatedAt(createdAt);
        loan.setUpdatedAt(updatedAt);
        loan.setInstallments(new ArrayList<>());
        loan.setDisbursements(new ArrayList<>());
        loan.setCharges(new ArrayList<>());
        return loan;
    }

    public UUID getLoanId() { return loanId; }
    public UUID getTenantId() { return tenantId; }

    protected LoanJpaEntity() {}
}
