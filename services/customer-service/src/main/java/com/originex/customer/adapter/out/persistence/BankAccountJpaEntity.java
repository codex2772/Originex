package com.originex.customer.adapter.out.persistence;

import com.originex.customer.domain.model.BankAccount;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bank_accounts")
public class BankAccountJpaEntity {

    @Id
    @Column(name = "bank_account_id")
    private UUID bankAccountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerJpaEntity customer;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "account_number_masked")
    private String accountNumberMasked;

    @Column(name = "ifsc_code", nullable = false)
    private String ifscCode;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "account_holder_name", nullable = false)
    private String accountHolderName;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "verified")
    private boolean verified;

    @Column(name = "is_primary")
    private boolean primary;

    @Column(name = "created_at")
    private Instant createdAt;

    public static BankAccountJpaEntity fromDomain(BankAccount domain, CustomerJpaEntity customer) {
        BankAccountJpaEntity e = new BankAccountJpaEntity();
        e.bankAccountId = domain.getBankAccountId();
        e.customer = customer;
        e.tenantId = customer.getTenantId();
        e.accountNumber = domain.getAccountNumber();
        e.accountNumberMasked = domain.getAccountNumberMasked();
        e.ifscCode = domain.getIfscCode();
        e.bankName = domain.getBankName();
        e.accountHolderName = domain.getAccountHolderName();
        e.accountType = domain.getAccountType().name();
        e.verified = domain.isVerified();
        e.primary = domain.isPrimary();
        e.createdAt = Instant.now();
        return e;
    }

    public BankAccount toDomain() {
        BankAccount ba = new BankAccount();
        ba.setBankAccountId(bankAccountId);
        ba.setAccountNumber(accountNumber);
        ba.setAccountNumberMasked(accountNumberMasked);
        ba.setIfscCode(ifscCode);
        ba.setBankName(bankName);
        ba.setAccountHolderName(accountHolderName);
        ba.setAccountType(BankAccount.BankAccountType.valueOf(accountType));
        ba.setVerified(verified);
        ba.setPrimary(primary);
        return ba;
    }

    protected BankAccountJpaEntity() {}
}
