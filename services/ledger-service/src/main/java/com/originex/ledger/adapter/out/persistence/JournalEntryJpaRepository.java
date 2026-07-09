package com.originex.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryJpaRepository extends JpaRepository<JournalEntryJpaEntity, UUID> {

    @Query("SELECT j FROM JournalEntryJpaEntity j WHERE j.tenantId = :tenantId AND j.entryId = :entryId")
    Optional<JournalEntryJpaEntity> findByTenantAndId(UUID tenantId, UUID entryId);
}
