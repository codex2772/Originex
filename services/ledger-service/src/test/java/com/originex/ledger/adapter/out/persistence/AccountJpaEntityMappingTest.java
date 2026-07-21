package com.originex.ledger.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.ledger.domain.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fix for #5. {@code AccountJpaEntity.toDomain()} previously rebuilt the
 * domain {@link Account} via {@code Account.open()}, which mints a fresh random
 * {@code accountId} and a zero balance — so every account read from the DB came back
 * with a <b>different identity</b> and a <b>lost balance</b>. Re-saving such an object
 * then INSERTed a new row carrying the existing {@code account_number}, violating
 * {@code idx_account_number}, and (had the constraint not fired) would have persisted
 * a wrong, zeroed balance.
 *
 * <p>This is the fast, DB-free guard: a domain→entity→domain round-trip must preserve
 * identity and balance. If {@code toDomain()} reverts to {@code Account.open}, the id
 * and balance assertions fail.
 */
@DisplayName("AccountJpaEntity mapping — reload preserves identity and balance (#5)")
class AccountJpaEntityMappingTest {

    @Test
    @DisplayName("toDomain preserves the persisted accountId, balance and cached state")
    void roundTripPreservesIdentityAndBalance() {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        Instant openedAt = Instant.parse("2026-01-01T00:00:00Z");

        Account original = Account.reconstitute(
                accountId, tenantId, "LR-abcdef12", "Loan Receivable - abcdef12",
                Account.AccountType.ASSET, Account.DebitCredit.DEBIT, "INR",
                Money.of(new BigDecimal("1500.00"), "INR"),
                Account.AccountStatus.ACTIVE, "1100", loanId, null,
                7L, openedAt, null);

        // The path the posting loop takes: entity from domain, then domain back from entity.
        Account reloaded = AccountJpaEntity.fromDomain(original).toDomain();

        assertThat(reloaded.getAccountId())
                .as("identity must survive the reload — the #5 duplicate-key cause was a new random id here")
                .isEqualTo(accountId);
        assertThat(reloaded.getBalance().getAmount())
                .as("balance must survive the reload — Account.open() would have zeroed it")
                .isEqualByComparingTo("1500.00");
        assertThat(reloaded.getTenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getAccountNumber()).isEqualTo("LR-abcdef12");
        assertThat(reloaded.getAccountType()).isEqualTo(Account.AccountType.ASSET);
        assertThat(reloaded.getNormalBalance()).isEqualTo(Account.DebitCredit.DEBIT);
        assertThat(reloaded.getStatus()).isEqualTo(Account.AccountStatus.ACTIVE);
        assertThat(reloaded.getGlCode()).isEqualTo("1100");
        assertThat(reloaded.getLoanId()).isEqualTo(loanId);
        assertThat(reloaded.getLastEventSequence())
                .as("event sequence must survive so applyPosting continues from the right point")
                .isEqualTo(7L);
        assertThat(reloaded.getOpenedAt()).isEqualTo(openedAt);
    }

    @Test
    @DisplayName("a posting applied after reload accumulates onto the persisted balance, not zero")
    void postingAfterReloadAccumulatesOntoPersistedBalance() {
        Account original = Account.reconstitute(
                UUID.randomUUID(), UUID.randomUUID(), "TST-A", "Test Asset",
                Account.AccountType.ASSET, Account.DebitCredit.DEBIT, "INR",
                Money.of(new BigDecimal("100.00"), "INR"),
                Account.AccountStatus.ACTIVE, "1100", null, null,
                1L, Instant.now(), null);

        Account reloaded = AccountJpaEntity.fromDomain(original).toDomain();
        reloaded.applyPosting(Account.DebitCredit.DEBIT, Money.of(new BigDecimal("50.00"), "INR"));

        assertThat(reloaded.getBalance().getAmount())
                .as("100 (persisted) + 50 (new debit) = 150; with the old bug the base would be 0 → 50")
                .isEqualByComparingTo("150.00");
    }
}
