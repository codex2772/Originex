package com.originex.lms.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanJpaRepository extends JpaRepository<LoanJpaEntity, UUID> {

    @Query("SELECT l FROM LoanJpaEntity l WHERE l.tenantId = :tenantId AND l.loanId = :loanId")
    Optional<LoanJpaEntity> findByTenantAndId(UUID tenantId, UUID loanId);

    @Query("SELECT l FROM LoanJpaEntity l WHERE l.tenantId = :tenantId AND l.applicationId = :applicationId")
    Optional<LoanJpaEntity> findByTenantAndApplicationId(UUID tenantId, UUID applicationId);

    // Accrual eligibility, keyset-paginated by loanId. The status filter matches
    // the partial index idx_loans_accrual (WHERE status = 'ACTIVE').
    @Query("SELECT l FROM LoanJpaEntity l "
            + "WHERE l.status = com.originex.lms.domain.model.LoanStatus.ACTIVE "
            + "AND (l.lastAccrualDate IS NULL OR l.lastAccrualDate < :asOf) "
            + "AND (:afterLoanId IS NULL OR l.loanId > :afterLoanId) "
            + "ORDER BY l.loanId")
    List<LoanJpaEntity> findAccruable(LocalDate asOf, UUID afterLoanId, Pageable pageable);

    // Delinquency recompute set, keyset-paginated by loanId: ACTIVE/NPA loans that
    // are overdue for :asOf or still carry a non-zero DPD (so cured loans reset).
    @Query("SELECT l FROM LoanJpaEntity l "
            + "WHERE l.status IN (com.originex.lms.domain.model.LoanStatus.ACTIVE, "
            + "                   com.originex.lms.domain.model.LoanStatus.NPA) "
            + "AND l.nextDueDate IS NOT NULL "
            + "AND (l.nextDueDate < :asOf OR l.dpd > 0) "
            + "AND (:afterLoanId IS NULL OR l.loanId > :afterLoanId) "
            + "ORDER BY l.loanId")
    List<LoanJpaEntity> findDelinquent(LocalDate asOf, UUID afterLoanId, Pageable pageable);
}
