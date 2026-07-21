package com.originex.lms.adapter.out.persistence;

import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.model.Loan;
import org.hibernate.Hibernate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class LoanPersistenceAdapter implements LoanRepository {

    private final LoanJpaRepository jpaRepository;

    public LoanPersistenceAdapter(LoanJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Loan save(Loan loan) {
        LoanJpaEntity entity = LoanJpaEntity.fromDomain(loan);
        LoanJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Loan> findById(UUID tenantId, UUID loanId) {
        return jpaRepository.findByTenantAndId(tenantId, loanId)
                .map(LoanPersistenceAdapter::toDomainWithChildren);
    }

    @Override
    public Optional<Loan> findByApplicationId(UUID tenantId, UUID applicationId) {
        return jpaRepository.findByTenantAndApplicationId(tenantId, applicationId)
                .map(LoanPersistenceAdapter::toDomainWithChildren);
    }

    /**
     * Maps a loaded loan to the domain with its installment and disbursement
     * collections rehydrated. Both are LAZY bags; initializing them separately
     * (two SELECTs) avoids the {@code MultipleBagFetchException} a single
     * join-fetch of two lists would raise. Callers of {@code findById} /
     * {@code findByApplicationId} run in a transaction, so the session is open.
     */
    private static Loan toDomainWithChildren(LoanJpaEntity entity) {
        Hibernate.initialize(entity.getInstallmentEntities());
        Hibernate.initialize(entity.getDisbursementEntities());
        return entity.toDomain();
    }

    @Override
    public List<Loan> findAccruable(LocalDate asOf, UUID afterLoanId, int limit) {
        return jpaRepository.findAccruable(asOf, afterLoanId, PageRequest.of(0, limit))
                .stream().map(LoanJpaEntity::toDomain).toList();
    }

    @Override
    public List<Loan> findDelinquent(LocalDate asOf, UUID afterLoanId, int limit) {
        // Lean projection for eligibility only (no children); the DPD processor
        // re-loads each loan with its children before saving.
        return jpaRepository.findDelinquent(asOf, afterLoanId, PageRequest.of(0, limit))
                .stream().map(LoanJpaEntity::toDomain).toList();
    }
}
