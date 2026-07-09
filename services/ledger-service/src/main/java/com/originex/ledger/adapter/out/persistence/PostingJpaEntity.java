package com.originex.ledger.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "postings")
public class PostingJpaEntity {

    @Id
    @Column(name = "posting_id")
    private UUID postingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id", nullable = false)
    private JournalEntryJpaEntity journalEntry;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "narration")
    private String narration;

    @Column(name = "posted_at")
    private Instant postedAt;

    public static PostingJpaEntity fromDomain(JournalEntry.Posting posting, JournalEntryJpaEntity entry) {
        PostingJpaEntity e = new PostingJpaEntity();
        e.postingId = posting.getPostingId();
        e.journalEntry = entry;
        e.tenantId = entry.getTenantId();
        e.accountId = posting.getAccountId();
        e.side = posting.getSide().name();
        e.amount = posting.getAmount().getAmount();
        e.currency = posting.getAmount().getCurrencyCode();
        e.narration = posting.getNarration();
        e.postedAt = Instant.now();
        return e;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getAccountId() { return accountId; }
    public String getSide() { return side; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getNarration() { return narration; }

    protected PostingJpaEntity() {}
}
