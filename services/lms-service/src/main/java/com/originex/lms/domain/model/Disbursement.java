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

    /**
     * Rebuilds a disbursement from persisted state (adapter/JPA use only),
     * restoring its reference, status, and timestamps.
     */
    public static Disbursement reconstitute(UUID disbursementId, Money amount, String beneficiaryAccount,
                                            String paymentReference, DisbursementStatus status,
                                            Instant initiatedAt, Instant completedAt) {
        Disbursement d = new Disbursement();
        d.disbursementId = disbursementId;
        d.amount = amount;
        d.beneficiaryAccount = beneficiaryAccount;
        d.paymentReference = paymentReference;
        d.status = status;
        d.initiatedAt = initiatedAt;
        d.completedAt = completedAt;
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
