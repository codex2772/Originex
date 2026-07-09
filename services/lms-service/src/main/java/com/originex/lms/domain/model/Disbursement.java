package com.originex.lms.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.util.UUID;

public class Disbursement {

    private UUID disbursementId;
    private Money amount;
    private String beneficiaryAccount;
    private String paymentReference;
    private DisbursementStatus status;
    private Instant initiatedAt;
    private Instant completedAt;

    public static Disbursement create(Money amount, String beneficiaryAccount) {
        Disbursement d = new Disbursement();
        d.disbursementId = UUID.randomUUID();
        d.amount = amount;
        d.beneficiaryAccount = beneficiaryAccount;
        d.status = DisbursementStatus.INITIATED;
        d.initiatedAt = Instant.now();
        return d;
    }

    public void confirm(String paymentReference) {
        this.paymentReference = paymentReference;
        this.status = DisbursementStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = DisbursementStatus.FAILED;
    }

    public UUID getDisbursementId() { return disbursementId; }
    public Money getAmount() { return amount; }
    public String getBeneficiaryAccount() { return beneficiaryAccount; }
    public String getPaymentReference() { return paymentReference; }
    public DisbursementStatus getStatus() { return status; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public Disbursement() {}

    public enum DisbursementStatus { INITIATED, COMPLETED, FAILED, REVERSED }
}
