package com.originex.payment.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * PaymentOrder Aggregate Root — represents a single atomic payment instruction.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Amount must be positive (no zero-value payments)</li>
 *   <li>A completed order cannot be cancelled or retried</li>
 *   <li>A failed order can be retried up to maxRetries times</li>
 *   <li>Every state transition is logged via domain events (outbox)</li>
 * </ul>
 *
 * <p>Payment types:
 * <ul>
 *   <li>DISBURSEMENT — funds sent to borrower bank account (NEFT/RTGS/IMPS)</li>
 *   <li>REPAYMENT_COLLECTION — NACH auto-debit or manual UPI/Net Banking</li>
 *   <li>REFUND — excess payment returned to borrower</li>
 * </ul>
 */
public class PaymentOrder {

    private UUID paymentOrderId;
    private UUID tenantId;
    private UUID loanId;
    private UUID customerId;
    private PaymentType paymentType;
    private PaymentRail paymentRail;      // NEFT, RTGS, IMPS, UPI, NACH, INTERNAL
    private PaymentOrderStatus status;
    private Money amount;

    // Beneficiary details (for disbursement)
    private String beneficiaryAccountNumber;
    private String beneficiaryIfsc;
    private String beneficiaryName;
    private String beneficiaryBankName;

    // Mandate details (for NACH collection)
    private String mandateId;
    private String umrn;                   // Unique Mandate Reference Number

    // Transaction tracking
    private String paymentReference;       // Our internal reference
    private String externalTransactionId;  // UTR / UPI ref from payment rail
    private String bankReferenceNumber;
    private String failureReason;
    private int retryCount;
    private int maxRetries;

    // Timing
    private Instant scheduledAt;
    private Instant initiatedAt;
    private Instant completedAt;
    private Instant failedAt;
    private Instant createdAt;
    private Instant updatedAt;

    private long version;

    // ─── Factory Methods ───

    public static PaymentOrder createDisbursement(UUID tenantId, UUID loanId, UUID customerId,
                                                   Money amount, String accountNumber,
                                                   String ifscCode, String beneficiaryName,
                                                   String bankName, PaymentRail rail) {
        validateAmount(amount);
        PaymentOrder o = new PaymentOrder();
        o.paymentOrderId = UUID.randomUUID();
        o.tenantId = tenantId;
        o.loanId = loanId;
        o.customerId = customerId;
        o.paymentType = PaymentType.DISBURSEMENT;
        o.paymentRail = rail != null ? rail : PaymentRail.NEFT;
        o.status = PaymentOrderStatus.CREATED;
        o.amount = amount;
        o.beneficiaryAccountNumber = accountNumber;
        o.beneficiaryIfsc = ifscCode;
        o.beneficiaryName = beneficiaryName;
        o.beneficiaryBankName = bankName;
        o.paymentReference = generateReference("DISB", tenantId);
        o.maxRetries = 3;
        o.retryCount = 0;
        o.createdAt = Instant.now();
        o.updatedAt = Instant.now();
        o.scheduledAt = Instant.now();
        return o;
    }

    public static PaymentOrder createNachCollection(UUID tenantId, UUID loanId, UUID customerId,
                                                     Money amount, String mandateId, String umrn) {
        validateAmount(amount);
        PaymentOrder o = new PaymentOrder();
        o.paymentOrderId = UUID.randomUUID();
        o.tenantId = tenantId;
        o.loanId = loanId;
        o.customerId = customerId;
        o.paymentType = PaymentType.REPAYMENT_COLLECTION;
        o.paymentRail = PaymentRail.NACH;
        o.status = PaymentOrderStatus.CREATED;
        o.amount = amount;
        o.mandateId = mandateId;
        o.umrn = umrn;
        o.paymentReference = generateReference("COLL", tenantId);
        o.maxRetries = 2;
        o.retryCount = 0;
        o.createdAt = Instant.now();
        o.updatedAt = Instant.now();
        o.scheduledAt = Instant.now();
        return o;
    }

    // ─── State Machine ───

    public void initiate() {
        assertStatus(PaymentOrderStatus.CREATED, PaymentOrderStatus.RETRY_PENDING);
        this.status = PaymentOrderStatus.INITIATED;
        this.initiatedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markProcessing() {
        assertStatus(PaymentOrderStatus.INITIATED);
        this.status = PaymentOrderStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void complete(String externalTransactionId, String bankReferenceNumber) {
        assertStatus(PaymentOrderStatus.PROCESSING, PaymentOrderStatus.INITIATED);
        this.status = PaymentOrderStatus.COMPLETED;
        this.externalTransactionId = externalTransactionId;
        this.bankReferenceNumber = bankReferenceNumber;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void fail(String reason) {
        if (this.status == PaymentOrderStatus.COMPLETED) {
            throw new IllegalStateException("Cannot fail a completed payment order");
        }
        this.status = PaymentOrderStatus.FAILED;
        this.failureReason = reason;
        this.failedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void scheduleRetry() {
        assertStatus(PaymentOrderStatus.FAILED);
        if (retryCount >= maxRetries) {
            this.status = PaymentOrderStatus.PERMANENTLY_FAILED;
            this.updatedAt = Instant.now();
            return;
        }
        this.retryCount++;
        this.status = PaymentOrderStatus.RETRY_PENDING;
        this.failureReason = null;
        this.updatedAt = Instant.now();
    }

    public void cancel(String reason) {
        if (this.status == PaymentOrderStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a completed payment order");
        }
        if (this.status == PaymentOrderStatus.PROCESSING) {
            throw new IllegalStateException("Cannot cancel a payment already being processed");
        }
        this.status = PaymentOrderStatus.CANCELLED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    // ─── Helpers ───

    private static void validateAmount(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
    }

    private static String generateReference(String prefix, UUID tenantId) {
        return prefix + "-" + System.currentTimeMillis() + "-" +
                tenantId.toString().substring(0, 8).toUpperCase();
    }

    private void assertStatus(PaymentOrderStatus... allowed) {
        for (PaymentOrderStatus s : allowed) {
            if (this.status == s) return;
        }
        throw new IllegalStateException(
                "Invalid payment order transition from " + this.status + ". Allowed: " + java.util.Arrays.toString(allowed));
    }

    public boolean isTerminal() {
        return status == PaymentOrderStatus.COMPLETED
                || status == PaymentOrderStatus.PERMANENTLY_FAILED
                || status == PaymentOrderStatus.CANCELLED;
    }

    // ─── Accessors ───
    public UUID getPaymentOrderId() { return paymentOrderId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getLoanId() { return loanId; }
    public UUID getCustomerId() { return customerId; }
    public PaymentType getPaymentType() { return paymentType; }
    public PaymentRail getPaymentRail() { return paymentRail; }
    public PaymentOrderStatus getStatus() { return status; }
    public Money getAmount() { return amount; }
    public String getBeneficiaryAccountNumber() { return beneficiaryAccountNumber; }
    public String getBeneficiaryIfsc() { return beneficiaryIfsc; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getBeneficiaryBankName() { return beneficiaryBankName; }
    public String getMandateId() { return mandateId; }
    public String getUmrn() { return umrn; }
    public String getPaymentReference() { return paymentReference; }
    public String getExternalTransactionId() { return externalTransactionId; }
    public String getBankReferenceNumber() { return bankReferenceNumber; }
    public String getFailureReason() { return failureReason; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFailedAt() { return failedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    // For JPA reconstruction
    public PaymentOrder() {}
    public void setPaymentOrderId(UUID id) { this.paymentOrderId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setLoanId(UUID id) { this.loanId = id; }
    public void setCustomerId(UUID id) { this.customerId = id; }
    public void setPaymentType(PaymentType t) { this.paymentType = t; }
    public void setPaymentRail(PaymentRail r) { this.paymentRail = r; }
    public void setStatus(PaymentOrderStatus s) { this.status = s; }
    public void setAmount(Money m) { this.amount = m; }
    public void setBeneficiaryAccountNumber(String s) { this.beneficiaryAccountNumber = s; }
    public void setBeneficiaryIfsc(String s) { this.beneficiaryIfsc = s; }
    public void setBeneficiaryName(String s) { this.beneficiaryName = s; }
    public void setBeneficiaryBankName(String s) { this.beneficiaryBankName = s; }
    public void setMandateId(String s) { this.mandateId = s; }
    public void setUmrn(String s) { this.umrn = s; }
    public void setPaymentReference(String s) { this.paymentReference = s; }
    public void setExternalTransactionId(String s) { this.externalTransactionId = s; }
    public void setBankReferenceNumber(String s) { this.bankReferenceNumber = s; }
    public void setFailureReason(String s) { this.failureReason = s; }
    public void setRetryCount(int i) { this.retryCount = i; }
    public void setMaxRetries(int i) { this.maxRetries = i; }
    public void setScheduledAt(Instant i) { this.scheduledAt = i; }
    public void setInitiatedAt(Instant i) { this.initiatedAt = i; }
    public void setCompletedAt(Instant i) { this.completedAt = i; }
    public void setFailedAt(Instant i) { this.failedAt = i; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }
    public void setVersion(long v) { this.version = v; }

    public enum PaymentType { DISBURSEMENT, REPAYMENT_COLLECTION, REFUND, FEE_COLLECTION }
    public enum PaymentRail { NEFT, RTGS, IMPS, UPI, NACH, INTERNAL }
    public enum PaymentOrderStatus {
        CREATED, INITIATED, PROCESSING, COMPLETED, FAILED, RETRY_PENDING, PERMANENTLY_FAILED, CANCELLED
    }
}
