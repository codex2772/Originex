package com.originex.payment.adapter.out.persistence;

import com.originex.payment.domain.model.NachMandate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface NachMandateJpaRepository extends JpaRepository<NachMandateJpaEntity, UUID> {

    @Query("SELECT m FROM NachMandateJpaEntity m WHERE m.tenantId = :tenantId AND m.mandateId = :id")
    Optional<NachMandateJpaEntity> findByTenantAndId(UUID tenantId, UUID id);

    @Query("SELECT m FROM NachMandateJpaEntity m WHERE m.tenantId = :tenantId AND m.loanId = :loanId AND m.status = 'ACTIVE'")
    Optional<NachMandateJpaEntity> findActiveByLoanId(UUID tenantId, UUID loanId);
}
