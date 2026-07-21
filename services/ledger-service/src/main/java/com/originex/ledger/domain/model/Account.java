package com.originex.ledger.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Ledger Account — represents a GL account in the chart of accounts.
 * Balance is derived from events (event-sourced) but cached in snapshots for fast reads.
 */
public class Account {

    private UUID accountId;
    private UUID tenantId;
    private String accountNumber;
    private String name;
    private AccountType accountType;
    private DebitCredit normalBalance;
    private String currency;
    private Money balance;           // Derived from events, cached in snapshot
    private AccountStatus status;
    private String glCode;
    private UUID loanId;             // Nullable — for loan-specific sub-accounts
    private UUID customerId;         // Nullable — for customer-specific accounts
    private long lastEventSequence;
    private Instant openedAt;
    private Instant closedAt;

    public static Account open(UUID tenantId, String accountNumber, String name,
                               AccountType type, String glCode, String currency) {
        Account account = new Account();
        account.accountId = UUID.randomUUID();
        account.tenantId = tenantId;
        account.accountNumber = accountNumber;
        account.name = name;
        account.accountType = type;
        account.normalBalance = type.normalBalance();
        account.currency = currency;
        account.balance = Money.zero(currency);
        account.status = AccountStatus.ACTIVE;
        account.glCode = glCode;
        account.lastEventSequence = 0;
        account.openedAt = Instant.now();
        return account;
    }

    /**
     * Rehydrate an account from its persisted snapshot, preserving identity and
     * cached state. Unlike {@link #open}, this does <b>not</b> mint a new id or zero
     * the balance — it is the "dedicated reconstruction constructor" that the JPA
     * mapper needs. Using {@code open()} to reload (as the mapper previously did)
     * returned every account with a fresh random {@code accountId} and a zeroed
     * balance; re-saving then INSERTed a duplicate {@code account_number} (violating
     * {@code idx_account_number}) and silently discarded the real balance. See #5.
     */
    public static Account reconstitute(UUID accountId, UUID tenantId, String accountNumber,
                                       String name, AccountType type, DebitCredit normalBalance,
                                       String currency, Money balance, AccountStatus status,
                                       String glCode, UUID loanId, UUID customerId,
                                       long lastEventSequence, Instant openedAt, Instant closedAt) {
        Account account = new Account();
        account.accountId = accountId;
        account.tenantId = tenantId;
        account.accountNumber = accountNumber;
        account.name = name;
        account.accountType = type;
        account.normalBalance = normalBalance;
        account.currency = currency;
        account.balance = balance;
        account.status = status;
        account.glCode = glCode;
        account.loanId = loanId;
        account.customerId = customerId;
        account.lastEventSequence = lastEventSequence;
        account.openedAt = openedAt;
        account.closedAt = closedAt;
        return account;
    }

    /**
     * Apply a posting to this account (updates cached balance).
     */
    public void applyPosting(DebitCredit side, Money amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account is not active: " + this.accountId);
        }

        if (side == normalBalance) {
            // Posting on normal side increases balance
            this.balance = this.balance.add(amount);
        } else {
            // Posting on opposite side decreases balance
            this.balance = this.balance.subtract(amount);
        }
        this.lastEventSequence++;
    }

    public void freeze() { this.status = AccountStatus.FROZEN; }
    public void close() { this.status = AccountStatus.CLOSED; this.closedAt = Instant.now(); }

    // Accessors
    public UUID getAccountId() { return accountId; }
    public UUID getTenantId() { return tenantId; }
    public String getAccountNumber() { return accountNumber; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public DebitCredit getNormalBalance() { return normalBalance; }
    public String getCurrency() { return currency; }
    public Money getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public String getGlCode() { return glCode; }
    public UUID getLoanId() { return loanId; }
    public UUID getCustomerId() { return customerId; }
    public long getLastEventSequence() { return lastEventSequence; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }

    public void setLoanId(UUID loanId) { this.loanId = loanId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    protected Account() {}

    public enum AccountType {
        ASSET(DebitCredit.DEBIT),
        LIABILITY(DebitCredit.CREDIT),
        EQUITY(DebitCredit.CREDIT),
        REVENUE(DebitCredit.CREDIT),
        EXPENSE(DebitCredit.DEBIT);

        private final DebitCredit normalBalance;

        AccountType(DebitCredit nb) { this.normalBalance = nb; }
        public DebitCredit normalBalance() { return normalBalance; }
    }

    public enum DebitCredit { DEBIT, CREDIT }
    public enum AccountStatus { ACTIVE, FROZEN, CLOSED }
}
