package com.originex.template.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository interface.
 * Infrastructure adapter — NOT exposed to application layer directly.
 */
public interface SampleJpaRepository extends JpaRepository<SampleJpaEntity, UUID> {

    @Query("SELECT s FROM SampleJpaEntity s WHERE s.tenantId = :tenantId AND s.sampleId = :sampleId")
    Optional<SampleJpaEntity> findByTenantAndId(UUID tenantId, UUID sampleId);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
