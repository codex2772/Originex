package com.originex.payment.adapter.out.persistence;

import com.originex.payment.application.port.out.NachMandateRepository;
import com.originex.payment.domain.model.NachMandate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class NachMandatePersistenceAdapter implements NachMandateRepository {

    private final NachMandateJpaRepository jpa;

    public NachMandatePersistenceAdapter(NachMandateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public NachMandate save(NachMandate mandate) {
        return jpa.save(NachMandateJpaEntity.fromDomain(mandate)).toDomain();
    }

    @Override
    public Optional<NachMandate> findById(UUID tenantId, UUID mandateId) {
        return jpa.findByTenantAndId(tenantId, mandateId).map(NachMandateJpaEntity::toDomain);
    }

    @Override
    public Optional<NachMandate> findActiveByLoanId(UUID tenantId, UUID loanId) {
        return jpa.findActiveByLoanId(tenantId, loanId).map(NachMandateJpaEntity::toDomain);
    }
}
