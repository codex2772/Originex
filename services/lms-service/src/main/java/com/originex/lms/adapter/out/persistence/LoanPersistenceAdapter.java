package com.originex.lms.adapter.out.persistence;

import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.model.Loan;
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
                .map(LoanJpaEntity::toDomain);
    }

    @Override
    public Optional<Loan> findByApplicationId(UUID tenantId, UUID applicationId) {
        return jpaRepository.findByTenantAndApplicationId(tenantId, applicationId)
                .map(LoanJpaEntity::toDomain);
    }

    @Override
    public List<Loan> findAccruable(LocalDate asOf, UUID afterLoanId, int limit) {
        return jpaRepository.findAccruable(asOf, afterLoanId, PageRequest.of(0, limit))
                .stream().map(LoanJpaEntity::toDomain).toList();
    }
}
