package com.originex.los.adapter.out.persistence;

import com.originex.los.application.port.out.LoanApplicationRepository;
import com.originex.los.domain.model.LoanApplication;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Component
public class LoanApplicationPersistenceAdapter implements LoanApplicationRepository {

    private final LoanApplicationJpaRepository jpaRepository;

    public LoanApplicationPersistenceAdapter(LoanApplicationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public LoanApplication save(LoanApplication application) {
        LoanApplicationJpaEntity entity = LoanApplicationJpaEntity.fromDomain(application);
        LoanApplicationJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<LoanApplication> findById(UUID tenantId, UUID applicationId) {
        return jpaRepository.findByTenantAndId(tenantId, applicationId)
                .map(LoanApplicationJpaEntity::toDomain);
    }

    @Override
    public boolean existsByCustomerAndProduct(UUID tenantId, UUID customerId, String productCode, int recentDays) {
        Instant since = Instant.now().minus(recentDays, ChronoUnit.DAYS);
        return jpaRepository.existsRecentByCustomerAndProduct(tenantId, customerId, productCode, since);
    }
}
