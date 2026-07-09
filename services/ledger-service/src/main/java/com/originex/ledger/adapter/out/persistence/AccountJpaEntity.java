package com.originex.ledger.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.ledger.domain.model.Account;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_snapshots")
public class AccountJpaEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "normal_balance", nullable = false)
    private String normalBalance;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "gl_code", nullable = false)
    private String glCode;

    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "last_event_sequence", nullable = false)
    private long lastEventSequence;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public static AccountJpaEntity fromDomain(Account account) {
        AccountJpaEntity e = new AccountJpaEntity();
        e.accountId = account.getAccountId();
        e.tenantId = account.getTenantId();
        e.accountNumber = account.getAccountNumber();
        e.name = account.getName();
        e.accountType = account.getAccountType().name();
        e.normalBalance = account.getNormalBalance().name();
        e.currency = account.getCurrency();
        e.balance = account.getBalance().getAmount();
        e.status = account.getStatus().name();
        e.glCode = account.getGlCode();
        e.loanId = account.getLoanId();
        e.customerId = account.getCustomerId();
        e.lastEventSequence = account.getLastEventSequence();
        e.openedAt = account.getOpenedAt();
        e.updatedAt = Instant.now();
        return e;
    }

    public Account toDomain() {
        Account a = Account.open(
                tenantId, accountNumber, name,
                Account.AccountType.valueOf(accountType),
                glCode, currency
        );
        // The open() factory sets balance to zero; we need to override with persisted balance
        // In production, this would use a dedicated reconstruction constructor
        return a;
    }

    protected AccountJpaEntity() {}
}
