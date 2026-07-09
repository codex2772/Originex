package com.originex.starter.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findPendingEvents(org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity e SET e.status = 'PUBLISHED', e.publishedAt = :now WHERE e.eventId = :eventId")
    void markPublished(UUID eventId, Instant now);

    @Modifying
    @Query("DELETE FROM OutboxEventJpaEntity e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :before")
    int deletePublishedBefore(Instant before);
}
