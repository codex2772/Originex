package com.originex.payment.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.payment.domain.model.PaymentOrder;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_orders")
public class PaymentOrderJpaEntity {

    @Id
    @Column(name = "payment_order_id")
    private UUID paymentOrderId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false)
    private PaymentOrder.PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_rail", nullable = false)
    private PaymentOrder.PaymentRail paymentRail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentOrder.PaymentOrderStatus status;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "beneficiary_account_number")
    private String beneficiaryAccountNumber;

    @Column(name = "beneficiary_ifsc")
    private String beneficiaryIfsc;

    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    @Column(name = "beneficiary_bank_name")
    private String beneficiaryBankName;

    @Column(name = "mandate_id")
    private String mandateId;

    @Column(name = "umrn")
    private String umrn;

    @Column(name = "payment_reference", nullable = false, unique = true)
    private String paymentReference;

    @Column(name = "external_transaction_id")
    private String externalTransactionId;

    @Column(name = "bank_reference_number")
    private String bankReferenceNumber;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "max_retries")
    private int maxRetries;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "initiated_at")
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @jakarta.persistence.Version
    @Column(name = "version")
    private long version;

    public static PaymentOrderJpaEntity fromDomain(PaymentOrder o) {
        PaymentOrderJpaEntity e = new PaymentOrderJpaEntity();
        e.paymentOrderId = o.getPaymentOrderId();
        e.tenantId = o.getTenantId();
        e.loanId = o.getLoanId();
        e.customerId = o.getCustomerId();
        e.paymentType = o.getPaymentType();
        e.paymentRail = o.getPaymentRail();
        e.status = o.getStatus();
        e.amount = o.getAmount().getAmount();
        e.currency = o.getAmount().getCurrencyCode();
        e.beneficiaryAccountNumber = o.getBeneficiaryAccountNumber();
        e.beneficiaryIfsc = o.getBeneficiaryIfsc();
        e.beneficiaryName = o.getBeneficiaryName();
        e.beneficiaryBankName = o.getBeneficiaryBankName();
        e.mandateId = o.getMandateId();
        e.umrn = o.getUmrn();
        e.paymentReference = o.getPaymentReference();
        e.externalTransactionId = o.getExternalTransactionId();
        e.bankReferenceNumber = o.getBankReferenceNumber();
        e.failureReason = o.getFailureReason();
        e.retryCount = o.getRetryCount();
        e.maxRetries = o.getMaxRetries();
        e.scheduledAt = o.getScheduledAt();
        e.initiatedAt = o.getInitiatedAt();
        e.completedAt = o.getCompletedAt();
        e.failedAt = o.getFailedAt();
        e.createdAt = o.getCreatedAt() != null ? o.getCreatedAt() : Instant.now();
        e.updatedAt = Instant.now();
        e.version = o.getVersion();
        return e;
    }

    public PaymentOrder toDomain() {
        PaymentOrder o = new PaymentOrder();
        o.setPaymentOrderId(paymentOrderId);
        o.setTenantId(tenantId);
        o.setLoanId(loanId);
        o.setCustomerId(customerId);
        o.setPaymentType(paymentType);
        o.setPaymentRail(paymentRail);
        o.setStatus(status);
        o.setAmount(Money.of(amount, currency));
        o.setBeneficiaryAccountNumber(beneficiaryAccountNumber);
        o.setBeneficiaryIfsc(beneficiaryIfsc);
        o.setBeneficiaryName(beneficiaryName);
        o.setBeneficiaryBankName(beneficiaryBankName);
        o.setMandateId(mandateId);
        o.setUmrn(umrn);
        o.setPaymentReference(paymentReference);
        o.setExternalTransactionId(externalTransactionId);
        o.setBankReferenceNumber(bankReferenceNumber);
        o.setFailureReason(failureReason);
        o.setRetryCount(retryCount);
        o.setMaxRetries(maxRetries);
        o.setScheduledAt(scheduledAt);
        o.setInitiatedAt(initiatedAt);
        o.setCompletedAt(completedAt);
        o.setFailedAt(failedAt);
        o.setCreatedAt(createdAt);
        o.setUpdatedAt(updatedAt);
        o.setVersion(version);
        return o;
    }

    protected PaymentOrderJpaEntity() {}
}
