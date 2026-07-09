package com.originex.lms.domain.model;

import com.originex.common.money.Money;

import java.time.LocalDate;
import java.util.UUID;

/**
 * EMI installment within a repayment schedule.
 */
public class Installment {

    private UUID installmentId;
    private int installmentNumber;
    private LocalDate dueDate;
    private Money principalDue;
    private Money interestDue;
    private Money totalDue;
    private Money principalPaid;
    private Money interestPaid;
    private InstallmentStatus status;
    private LocalDate paidDate;

    public static Installment create(int number, LocalDate dueDate,
                                     Money principalDue, Money interestDue) {
        Installment inst = new Installment();
        inst.installmentId = UUID.randomUUID();
        inst.installmentNumber = number;
        inst.dueDate = dueDate;
        inst.principalDue = principalDue;
        inst.interestDue = interestDue;
        inst.totalDue = principalDue.add(interestDue);
        inst.principalPaid = Money.zero(principalDue.getCurrencyCode());
        inst.interestPaid = Money.zero(interestDue.getCurrencyCode());
        inst.status = InstallmentStatus.UPCOMING;
        return inst;
    }

    public void markDue() {
        if (this.status == InstallmentStatus.UPCOMING) {
            this.status = InstallmentStatus.DUE;
        }
    }

    public void markOverdue() {
        if (this.status == InstallmentStatus.DUE) {
            this.status = InstallmentStatus.OVERDUE;
        }
    }

    public void markPaid() {
        this.status = InstallmentStatus.PAID;
        this.paidDate = LocalDate.now();
    }

    public UUID getInstallmentId() { return installmentId; }
    public int getInstallmentNumber() { return installmentNumber; }
    public LocalDate getDueDate() { return dueDate; }
    public Money getPrincipalDue() { return principalDue; }
    public Money getInterestDue() { return interestDue; }
    public Money getTotalDue() { return totalDue; }
    public Money getPrincipalPaid() { return principalPaid; }
    public Money getInterestPaid() { return interestPaid; }
    public InstallmentStatus getStatus() { return status; }
    public LocalDate getPaidDate() { return paidDate; }

    public Installment() {}

    public enum InstallmentStatus {
        UPCOMING, DUE, OVERDUE, PAID, PARTIALLY_PAID, WAIVED
    }
}
