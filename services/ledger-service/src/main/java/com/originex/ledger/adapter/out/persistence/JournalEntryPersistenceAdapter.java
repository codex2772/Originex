package com.originex.ledger.adapter.out.persistence;

import com.originex.ledger.application.port.out.JournalEntryRepository;
import com.originex.ledger.domain.model.JournalEntry;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class JournalEntryPersistenceAdapter implements JournalEntryRepository {

    private final JournalEntryJpaRepository jpaRepository;

    public JournalEntryPersistenceAdapter(JournalEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public JournalEntry save(JournalEntry entry) {
        JournalEntryJpaEntity entity = JournalEntryJpaEntity.fromDomain(entry);
        jpaRepository.save(entity);
        return entry;
    }

    @Override
    public Optional<JournalEntry> findById(UUID tenantId, UUID entryId) {
        // Phase 3: Read from denormalized journal_entries table.
        // Phase 4 will add full event-sourcing replay from ledger_events.
        return jpaRepository.findByTenantAndId(tenantId, entryId)
                .map(JournalEntryJpaEntity::toDomain);
    }
}
