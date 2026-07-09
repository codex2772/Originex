package com.originex.lms.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface LoanJpaRepository extends JpaRepository<LoanJpaEntity, UUID> {

    @Query("SELECT l FROM LoanJpaEntity l WHERE l.tenantId = :tenantId AND l.loanId = :loanId")
    Optional<LoanJpaEntity> findByTenantAndId(UUID tenantId, UUID loanId);

    @Query("SELECT l FROM LoanJpaEntity l WHERE l.tenantId = :tenantId AND l.applicationId = :applicationId")
    Optional<LoanJpaEntity> findByTenantAndApplicationId(UUID tenantId, UUID applicationId);
}
