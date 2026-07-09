package com.originex.payment.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.payment.domain.model.NachMandate;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "nach_mandates")
public class NachMandateJpaEntity {

    @Id @Column(name = "mandate_id") private UUID mandateId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "loan_id", nullable = false) private UUID loanId;
    @Column(name = "customer_id") private UUID customerId;
    @Column(name = "umrn") private String umrn;
    @Column(name = "bank_account_number") private String bankAccountNumber;
    @Column(name = "ifsc_code") private String ifscCode;
    @Column(name = "bank_name") private String bankName;
    @Column(name = "account_holder_name") private String accountHolderName;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false) private NachMandate.MandateStatus status;
    @Column(name = "max_debit_amount") private BigDecimal maxDebitAmount;
    @Column(name = "currency") private String currency;
    @Column(name = "frequency") private String frequency;
    @Column(name = "start_date") private Instant startDate;
    @Column(name = "end_date") private Instant endDate;
    @Column(name = "registered_at") private Instant registeredAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancellation_reason") private String cancellationReason;
    @Column(name = "created_at") private Instant createdAt;

    public static NachMandateJpaEntity fromDomain(NachMandate m) {
        NachMandateJpaEntity e = new NachMandateJpaEntity();
        e.mandateId = m.getMandateId();
        e.tenantId = m.getTenantId();
        e.loanId = m.getLoanId();
        e.customerId = m.getCustomerId();
        e.umrn = m.getUmrn();
        e.bankAccountNumber = m.getBankAccountNumber();
        e.ifscCode = m.getIfscCode();
        e.bankName = m.getBankName();
        e.accountHolderName = m.getAccountHolderName();
        e.status = m.getStatus();
        e.maxDebitAmount = m.getMaxDebitAmount().getAmount();
        e.currency = m.getMaxDebitAmount().getCurrencyCode();
        e.frequency = m.getFrequency();
        e.startDate = m.getStartDate();
        e.endDate = m.getEndDate();
        e.registeredAt = m.getRegisteredAt();
        e.createdAt = m.getCreatedAt() != null ? m.getCreatedAt() : Instant.now();
        return e;
    }

    public NachMandate toDomain() {
        NachMandate m = new NachMandate();
        m.setMandateId(mandateId); m.setTenantId(tenantId); m.setLoanId(loanId);
        m.setCustomerId(customerId); m.setUmrn(umrn); m.setBankAccountNumber(bankAccountNumber);
        m.setIfscCode(ifscCode); m.setBankName(bankName); m.setAccountHolderName(accountHolderName);
        m.setStatus(status); m.setMaxDebitAmount(Money.of(maxDebitAmount, currency));
        m.setFrequency(frequency); m.setStartDate(startDate); m.setEndDate(endDate);
        m.setRegisteredAt(registeredAt); m.setCancelledAt(cancelledAt);
        m.setCancellationReason(cancellationReason); m.setCreatedAt(createdAt);
        return m;
    }

    protected NachMandateJpaEntity() {}
}
