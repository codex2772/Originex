package com.originex.partner.adapter.out.persistence;

import com.originex.partner.domain.model.IntegrationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IntegrationRequestJpaRepository extends JpaRepository<IntegrationRequestJpaEntity, UUID> {

    @Query("""
        SELECT r FROM IntegrationRequestJpaEntity r
        WHERE r.tenantId = :tenantId AND r.partnerType = :type AND r.referenceId = :referenceId
          AND r.status = 'SUCCESS' AND r.cacheExpiresAt > :now
        ORDER BY r.respondedAt DESC
    """)
    List<IntegrationRequestJpaEntity> findValidCache(UUID tenantId, IntegrationRequest.PartnerType type,
                                                     String referenceId, Instant now);
}
