package com.originex.los.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoanApplicationJpaRepository extends JpaRepository<LoanApplicationJpaEntity, UUID> {

    @Query("SELECT a FROM LoanApplicationJpaEntity a WHERE a.tenantId = :tenantId AND a.applicationId = :applicationId")
    Optional<LoanApplicationJpaEntity> findByTenantAndId(UUID tenantId, UUID applicationId);

    @Query("""
        SELECT COUNT(a) > 0 FROM LoanApplicationJpaEntity a
        WHERE a.tenantId = :tenantId
          AND a.customerId = :customerId
          AND a.productCode = :productCode
          AND a.submittedAt > :since
          AND a.status NOT IN ('REJECTED', 'WITHDRAWN', 'OFFER_EXPIRED')
    """)
    boolean existsRecentByCustomerAndProduct(UUID tenantId, UUID customerId, String productCode, Instant since);
}
