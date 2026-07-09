package com.originex.ledger.application.port.out;

import com.originex.ledger.domain.model.JournalEntry;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository {

    JournalEntry save(JournalEntry entry);

    Optional<JournalEntry> findById(UUID tenantId, UUID entryId);
}
