package com.originex.lms.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.lms.domain.model.Disbursement;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disbursements")
public class DisbursementJpaEntity {

    @Id
    @Column(name = "disbursement_id")
    private UUID disbursementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id", nullable = false)
    private LoanJpaEntity loan;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "beneficiary_account")
    private String beneficiaryAccount;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "initiated_at")
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static DisbursementJpaEntity fromDomain(Disbursement d, LoanJpaEntity loan) {
        DisbursementJpaEntity e = new DisbursementJpaEntity();
        e.disbursementId = d.getDisbursementId();
        e.loan = loan;
        e.tenantId = loan.getTenantId();
        e.amount = d.getAmount().getAmount();
        e.beneficiaryAccount = d.getBeneficiaryAccount();
        e.paymentReference = d.getPaymentReference();
        e.status = d.getStatus().name();
        e.initiatedAt = d.getInitiatedAt();
        e.completedAt = d.getCompletedAt();
        return e;
    }

    /**
     * Rebuilds the domain disbursement. The amount is denominated in the owning
     * loan's {@code currency} (disbursements carry no currency column).
     */
    public Disbursement toDomain(String currency) {
        return Disbursement.reconstitute(
                disbursementId, Money.of(amount, currency), beneficiaryAccount, paymentReference,
                Disbursement.DisbursementStatus.valueOf(status), initiatedAt, completedAt);
    }

    protected DisbursementJpaEntity() {}
}
