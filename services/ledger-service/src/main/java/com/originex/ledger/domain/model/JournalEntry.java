package com.originex.ledger.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Journal Entry — immutable double-entry posting record.
 *
 * <p>Key invariant: SUM(debits) MUST EQUAL SUM(credits).
 * Once posted, never modified — corrections via reversal only.
 */
public class JournalEntry {

    private UUID entryId;
    private UUID tenantId;
    private JournalEntryType entryType;
    private LocalDate postingDate;
    private LocalDate valueDate;
    private String description;
    private String sourceSystem;
    private String sourceId;
    private String sourceEventId;
    private List<Posting> postings;
    private EntryStatus status;
    private UUID reversalOf;         // If this is a reversal, points to original
    private UUID reversedBy;         // If this was reversed, points to reversal
    private String postedBy;
    private Instant postedAt;

    /**
     * Create a balanced journal entry. Validates double-entry invariant.
     */
    public static JournalEntry create(UUID tenantId, JournalEntryType type,
                                      LocalDate postingDate, LocalDate valueDate,
                                      String description, String sourceSystem,
                                      String sourceId, String sourceEventId,
                                      List<Posting> postings, String postedBy) {

        // ─── INVARIANT: Must have at least 2 postings ───
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("Journal entry must have at least 2 postings");
        }

        // ─── INVARIANT: Debits must equal credits ───
        Money totalDebits = Money.zero(postings.get(0).getAmount().getCurrencyCode());
        Money totalCredits = Money.zero(postings.get(0).getAmount().getCurrencyCode());

        for (Posting p : postings) {
            if (!p.getAmount().isPositive()) {
                throw new IllegalArgumentException("Posting amount must be positive");
            }
            if (p.getSide() == Account.DebitCredit.DEBIT) {
                totalDebits = totalDebits.add(p.getAmount());
            } else {
                totalCredits = totalCredits.add(p.getAmount());
            }
        }

        if (!totalDebits.equals(totalCredits)) {
            throw new IllegalArgumentException(
                    "Debits (" + totalDebits + ") must equal Credits (" + totalCredits + ")");
        }

        JournalEntry entry = new JournalEntry();
        entry.entryId = UUID.randomUUID();
        entry.tenantId = tenantId;
        entry.entryType = type;
        entry.postingDate = postingDate;
        entry.valueDate = valueDate != null ? valueDate : postingDate;
        entry.description = description;
        entry.sourceSystem = sourceSystem;
        entry.sourceId = sourceId;
        entry.sourceEventId = sourceEventId;
        entry.postings = new ArrayList<>(postings);
        entry.status = EntryStatus.POSTED;
        entry.postedBy = postedBy;
        entry.postedAt = Instant.now();
        return entry;
    }

    /**
     * Create a reversal of this entry (mirror all postings).
     */
    public JournalEntry reverse(String reason, String reversedBy) {
        if (this.status == EntryStatus.REVERSED) {
            throw new IllegalStateException("Entry already reversed");
        }

        List<Posting> reversalPostings = this.postings.stream()
                .map(p -> Posting.create(
                        p.getAccountId(),
                        p.getSide() == Account.DebitCredit.DEBIT
                                ? Account.DebitCredit.CREDIT
                                : Account.DebitCredit.DEBIT,
                        p.getAmount(),
                        "REVERSAL: " + p.getNarration()
                ))
                .toList();

        JournalEntry reversal = JournalEntry.create(
                this.tenantId, this.entryType,
                LocalDate.now(), LocalDate.now(),
                "REVERSAL: " + reason,
                this.sourceSystem, this.sourceId, null,
                reversalPostings, reversedBy
        );

        reversal.reversalOf = this.entryId;
        this.reversedBy = reversal.entryId;
        this.status = EntryStatus.REVERSED;

        return reversal;
    }

    public UUID getEntryId() { return entryId; }
    public UUID getTenantId() { return tenantId; }
    public JournalEntryType getEntryType() { return entryType; }
    public LocalDate getPostingDate() { return postingDate; }
    public LocalDate getValueDate() { return valueDate; }
    public String getDescription() { return description; }
    public String getSourceSystem() { return sourceSystem; }
    public String getSourceId() { return sourceId; }
    public List<Posting> getPostings() { return Collections.unmodifiableList(postings); }
    public EntryStatus getStatus() { return status; }
    public UUID getReversalOf() { return reversalOf; }
    public Instant getPostedAt() { return postedAt; }

    protected JournalEntry() {}

    public enum JournalEntryType {
        DISBURSEMENT, REPAYMENT, INTEREST_ACCRUAL, FEE_LEVY,
        REVERSAL, WRITE_OFF, PROVISION, ADJUSTMENT, CLOSURE
    }

    public enum EntryStatus { POSTED, REVERSED }

    // ─── Posting (line item within a journal entry) ───
    public static class Posting {
        private UUID postingId;
        private UUID accountId;
        private Account.DebitCredit side;
        private Money amount;
        private String narration;

        public static Posting create(UUID accountId, Account.DebitCredit side,
                                     Money amount, String narration) {
            Posting p = new Posting();
            p.postingId = UUID.randomUUID();
            p.accountId = accountId;
            p.side = side;
            p.amount = amount;
            p.narration = narration;
            return p;
        }

        public UUID getPostingId() { return postingId; }
        public UUID getAccountId() { return accountId; }
        public Account.DebitCredit getSide() { return side; }
        public Money getAmount() { return amount; }
        public String getNarration() { return narration; }

        protected Posting() {}
    }
}
