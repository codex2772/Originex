package com.originex.ledger.domain.model;

import com.originex.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JournalEntry — Double-Entry Bookkeeping Invariants")
class JournalEntryTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID LOAN_ACCOUNT = UUID.randomUUID();
    private static final UUID BANK_ACCOUNT = UUID.randomUUID();

    @Nested
    @DisplayName("Double-Entry Invariant")
    class DoubleEntryInvariant {

        @Test
        void shouldCreateBalancedEntry() {
            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(LOAN_ACCOUNT, Account.DebitCredit.DEBIT,
                            Money.of("500000", "INR"), "Loan disbursement"),
                    JournalEntry.Posting.create(BANK_ACCOUNT, Account.DebitCredit.CREDIT,
                            Money.of("500000", "INR"), "Bank transfer out")
            );

            JournalEntry entry = JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.DISBURSEMENT,
                    LocalDate.of(2026, 7, 8),
                    null,
                    "Loan disbursement to customer",
                    "LMS", "LOAN-001", "evt-123",
                    postings, "SYSTEM"
            );

            assertThat(entry.getEntryId()).isNotNull();
            assertThat(entry.getStatus()).isEqualTo(JournalEntry.EntryStatus.POSTED);
            assertThat(entry.getPostings()).hasSize(2);
        }

        @Test
        void shouldRejectUnbalancedEntry() {
            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(LOAN_ACCOUNT, Account.DebitCredit.DEBIT,
                            Money.of("500000", "INR"), "Debit"),
                    JournalEntry.Posting.create(BANK_ACCOUNT, Account.DebitCredit.CREDIT,
                            Money.of("499999", "INR"), "Credit — short by 1")
            );

            assertThatThrownBy(() -> JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.DISBURSEMENT,
                    LocalDate.now(), null, "Unbalanced", "TEST", "1", null,
                    postings, "SYSTEM"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Debits")
                    .hasMessageContaining("must equal Credits");
        }

        @Test
        void shouldRejectSinglePosting() {
            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(LOAN_ACCOUNT, Account.DebitCredit.DEBIT,
                            Money.of("100", "INR"), "Single leg")
            );

            assertThatThrownBy(() -> JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.ADJUSTMENT,
                    LocalDate.now(), null, "Single", "TEST", "1", null,
                    postings, "SYSTEM"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 2");
        }

        @Test
        void shouldRejectZeroAmountPosting() {
            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(LOAN_ACCOUNT, Account.DebitCredit.DEBIT,
                            Money.of("0", "INR"), "Zero debit"),
                    JournalEntry.Posting.create(BANK_ACCOUNT, Account.DebitCredit.CREDIT,
                            Money.of("0", "INR"), "Zero credit")
            );

            assertThatThrownBy(() -> JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.ADJUSTMENT,
                    LocalDate.now(), null, "Zero", "TEST", "1", null,
                    postings, "SYSTEM"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    @Nested
    @DisplayName("Multi-Leg Entries")
    class MultiLeg {

        @Test
        void shouldAllowMultiLegBalancedEntry() {
            // Repayment split: interest + principal
            UUID interestAccount = UUID.randomUUID();
            UUID principalAccount = UUID.randomUUID();
            UUID cashAccount = UUID.randomUUID();

            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(cashAccount, Account.DebitCredit.DEBIT,
                            Money.of("25000", "INR"), "Cash received"),
                    JournalEntry.Posting.create(interestAccount, Account.DebitCredit.CREDIT,
                            Money.of("5000", "INR"), "Interest income"),
                    JournalEntry.Posting.create(principalAccount, Account.DebitCredit.CREDIT,
                            Money.of("20000", "INR"), "Principal repaid")
            );

            JournalEntry entry = JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.REPAYMENT,
                    LocalDate.now(), null,
                    "EMI payment — split allocation",
                    "LMS", "LOAN-001", "evt-456",
                    postings, "SYSTEM"
            );

            assertThat(entry.getPostings()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Reversals")
    class Reversals {

        @Test
        void shouldCreateReversalWithMirroredPostings() {
            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(LOAN_ACCOUNT, Account.DebitCredit.DEBIT,
                            Money.of("100000", "INR"), "Disbursement"),
                    JournalEntry.Posting.create(BANK_ACCOUNT, Account.DebitCredit.CREDIT,
                            Money.of("100000", "INR"), "Bank out")
            );

            JournalEntry original = JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.DISBURSEMENT,
                    LocalDate.now(), null, "Original", "LMS", "1", null,
                    postings, "SYSTEM"
            );

            JournalEntry reversal = original.reverse("Duplicate entry", "ADMIN");

            assertThat(reversal.getEntryId()).isNotEqualTo(original.getEntryId());
            assertThat(reversal.getReversalOf()).isEqualTo(original.getEntryId());
            assertThat(original.getStatus()).isEqualTo(JournalEntry.EntryStatus.REVERSED);

            // Reversal should mirror: CREDIT where was DEBIT, DEBIT where was CREDIT
            assertThat(reversal.getPostings().get(0).getSide()).isEqualTo(Account.DebitCredit.CREDIT);
            assertThat(reversal.getPostings().get(1).getSide()).isEqualTo(Account.DebitCredit.DEBIT);
        }

        @Test
        void shouldNotReverseAlreadyReversedEntry() {
            List<JournalEntry.Posting> postings = List.of(
                    JournalEntry.Posting.create(LOAN_ACCOUNT, Account.DebitCredit.DEBIT,
                            Money.of("10000", "INR"), "D"),
                    JournalEntry.Posting.create(BANK_ACCOUNT, Account.DebitCredit.CREDIT,
                            Money.of("10000", "INR"), "C")
            );

            JournalEntry entry = JournalEntry.create(
                    TENANT_ID,
                    JournalEntry.JournalEntryType.FEE_LEVY,
                    LocalDate.now(), null, "Fee", "LMS", "1", null,
                    postings, "SYSTEM"
            );

            entry.reverse("Mistake", "ADMIN");

            assertThatThrownBy(() -> entry.reverse("Again", "ADMIN"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already reversed");
        }
    }

    @Nested
    @DisplayName("Account Balance")
    class AccountBalance {

        @Test
        void shouldIncreaseOnNormalSidePosting() {
            Account loanReceivable = Account.open(
                    TENANT_ID, "LR-001", "Loan Receivable",
                    Account.AccountType.ASSET, "1100", "INR"
            );

            assertThat(loanReceivable.getBalance().isZero()).isTrue();

            // ASSET has DEBIT normal balance — debit increases
            loanReceivable.applyPosting(Account.DebitCredit.DEBIT, Money.of("500000", "INR"));
            assertThat(loanReceivable.getBalance().getAmount()).isEqualByComparingTo("500000.0000");

            // Credit decreases an ASSET account
            loanReceivable.applyPosting(Account.DebitCredit.CREDIT, Money.of("25000", "INR"));
            assertThat(loanReceivable.getBalance().getAmount()).isEqualByComparingTo("475000.0000");
        }

        @Test
        void shouldIncreaseOnCreditForLiability() {
            Account deposits = Account.open(
                    TENANT_ID, "LI-001", "Customer Deposits",
                    Account.AccountType.LIABILITY, "2100", "INR"
            );

            // LIABILITY has CREDIT normal balance — credit increases
            deposits.applyPosting(Account.DebitCredit.CREDIT, Money.of("100000", "INR"));
            assertThat(deposits.getBalance().getAmount()).isEqualByComparingTo("100000.0000");

            // Debit decreases a LIABILITY account
            deposits.applyPosting(Account.DebitCredit.DEBIT, Money.of("30000", "INR"));
            assertThat(deposits.getBalance().getAmount()).isEqualByComparingTo("70000.0000");
        }
    }
}
