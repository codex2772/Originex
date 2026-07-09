package com.originex.payment.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * NACH Mandate — represents a registered auto-debit mandate from a borrower.
 * Required for EMI auto-collection via NPCI NACH.
 *
 * <p>Lifecycle: PENDING_REGISTRATION → ACTIVE → CANCELLED / PAUSED
 */
public class NachMandate {

    private UUID mandateId;
    private UUID tenantId;
    private UUID loanId;
    private UUID customerId;
    private String umrn;                    // Unique Mandate Reference Number (from NPCI)
    private String bankAccountNumber;
    private String ifscCode;
    private String bankName;
    private String accountHolderName;
    private MandateStatus status;
    private Money maxDebitAmount;           // Maximum per-debit amount approved
    private String frequency;              // MONTHLY, ADHOC
    private Instant startDate;
    private Instant endDate;
    private Instant registeredAt;
    private Instant cancelledAt;
    private String cancellationReason;
    private Instant createdAt;

    public static NachMandate register(UUID tenantId, UUID loanId, UUID customerId,
                                        String bankAccountNumber, String ifscCode,
                                        String bankName, String accountHolderName,
                                        Money maxDebitAmount, Instant startDate, Instant endDate) {
        NachMandate m = new NachMandate();
        m.mandateId = UUID.randomUUID();
        m.tenantId = tenantId;
        m.loanId = loanId;
        m.customerId = customerId;
        m.bankAccountNumber = bankAccountNumber;
        m.ifscCode = ifscCode;
        m.bankName = bankName;
        m.accountHolderName = accountHolderName;
        m.status = MandateStatus.PENDING_REGISTRATION;
        m.maxDebitAmount = maxDebitAmount;
        m.frequency = "MONTHLY";
        m.startDate = startDate;
        m.endDate = endDate;
        m.createdAt = Instant.now();
        return m;
    }

    public void activate(String umrn) {
        this.umrn = umrn;
        this.status = MandateStatus.ACTIVE;
        this.registeredAt = Instant.now();
    }

    public void cancel(String reason) {
        this.status = MandateStatus.CANCELLED;
        this.cancellationReason = reason;
        this.cancelledAt = Instant.now();
    }

    public void pause() { this.status = MandateStatus.PAUSED; }
    public void resume() { this.status = MandateStatus.ACTIVE; }

    public boolean isActive() { return status == MandateStatus.ACTIVE; }

    // Accessors
    public UUID getMandateId() { return mandateId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getLoanId() { return loanId; }
    public UUID getCustomerId() { return customerId; }
    public String getUmrn() { return umrn; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public String getIfscCode() { return ifscCode; }
    public String getBankName() { return bankName; }
    public String getAccountHolderName() { return accountHolderName; }
    public MandateStatus getStatus() { return status; }
    public Money getMaxDebitAmount() { return maxDebitAmount; }
    public String getFrequency() { return frequency; }
    public Instant getStartDate() { return startDate; }
    public Instant getEndDate() { return endDate; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getCreatedAt() { return createdAt; }

    public NachMandate() {}
    public void setMandateId(UUID id) { this.mandateId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setLoanId(UUID id) { this.loanId = id; }
    public void setCustomerId(UUID id) { this.customerId = id; }
    public void setUmrn(String s) { this.umrn = s; }
    public void setBankAccountNumber(String s) { this.bankAccountNumber = s; }
    public void setIfscCode(String s) { this.ifscCode = s; }
    public void setBankName(String s) { this.bankName = s; }
    public void setAccountHolderName(String s) { this.accountHolderName = s; }
    public void setStatus(MandateStatus s) { this.status = s; }
    public void setMaxDebitAmount(Money m) { this.maxDebitAmount = m; }
    public void setFrequency(String s) { this.frequency = s; }
    public void setStartDate(Instant i) { this.startDate = i; }
    public void setEndDate(Instant i) { this.endDate = i; }
    public void setRegisteredAt(Instant i) { this.registeredAt = i; }
    public void setCancelledAt(Instant i) { this.cancelledAt = i; }
    public void setCancellationReason(String s) { this.cancellationReason = s; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }

    public enum MandateStatus { PENDING_REGISTRATION, ACTIVE, PAUSED, CANCELLED, EXPIRED, REJECTED }
}
