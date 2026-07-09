package com.originex.ledger.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntryJpaEntity {

    @Id
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "description")
    private String description;

    @Column(name = "source_system")
    private String sourceSystem;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "reversal_of")
    private UUID reversalOf;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "posted_by")
    private String postedBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostingJpaEntity> postings = new ArrayList<>();

    public static JournalEntryJpaEntity fromDomain(JournalEntry entry) {
        JournalEntryJpaEntity e = new JournalEntryJpaEntity();
        e.entryId = entry.getEntryId();
        e.tenantId = entry.getTenantId();
        e.entryType = entry.getEntryType().name();
        e.postingDate = entry.getPostingDate();
        e.valueDate = entry.getValueDate();
        e.description = entry.getDescription();
        e.sourceSystem = entry.getSourceSystem();
        e.sourceId = entry.getSourceId();
        e.status = entry.getStatus().name();
        e.reversalOf = entry.getReversalOf();
        e.postedAt = entry.getPostedAt();

        entry.getPostings().forEach(p -> {
            PostingJpaEntity pe = PostingJpaEntity.fromDomain(p, e);
            e.postings.add(pe);
        });

        return e;
    }

    public JournalEntry toDomain() {
        List<JournalEntry.Posting> domainPostings = postings.stream()
                .map(p -> JournalEntry.Posting.create(
                        p.getAccountId(),
                        Account.DebitCredit.valueOf(p.getSide()),
                        Money.of(p.getAmount(), p.getCurrency()),
                        p.getNarration()
                ))
                .toList();

        return JournalEntry.create(
                tenantId,
                JournalEntry.JournalEntryType.valueOf(entryType),
                postingDate,
                valueDate,
                description,
                sourceSystem,
                sourceId,
                sourceEventId,
                domainPostings,
                postedBy
        );
    }

    public UUID getTenantId() { return tenantId; }

    protected JournalEntryJpaEntity() {}
}
