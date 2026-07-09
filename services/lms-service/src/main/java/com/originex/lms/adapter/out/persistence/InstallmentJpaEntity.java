package com.originex.lms.adapter.out.persistence;

import com.originex.lms.domain.model.Installment;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "installments")
public class InstallmentJpaEntity {

    @Id
    @Column(name = "installment_id")
    private UUID installmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id", nullable = false)
    private LoanJpaEntity loan;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "installment_number", nullable = false)
    private int installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_due", nullable = false)
    private BigDecimal principalDue;

    @Column(name = "interest_due", nullable = false)
    private BigDecimal interestDue;

    @Column(name = "total_due", nullable = false)
    private BigDecimal totalDue;

    @Column(name = "principal_paid", nullable = false)
    private BigDecimal principalPaid;

    @Column(name = "interest_paid", nullable = false)
    private BigDecimal interestPaid;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    public static InstallmentJpaEntity fromDomain(Installment inst, LoanJpaEntity loan) {
        InstallmentJpaEntity e = new InstallmentJpaEntity();
        e.installmentId = inst.getInstallmentId();
        e.loan = loan;
        e.tenantId = loan.getTenantId();
        e.installmentNumber = inst.getInstallmentNumber();
        e.dueDate = inst.getDueDate();
        e.principalDue = inst.getPrincipalDue().getAmount();
        e.interestDue = inst.getInterestDue().getAmount();
        e.totalDue = inst.getTotalDue().getAmount();
        e.principalPaid = inst.getPrincipalPaid().getAmount();
        e.interestPaid = inst.getInterestPaid().getAmount();
        e.status = inst.getStatus().name();
        e.paidDate = inst.getPaidDate();
        return e;
    }

    protected InstallmentJpaEntity() {}
}
