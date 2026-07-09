package com.originex.partner.adapter.out.persistence;

import com.originex.partner.application.port.out.IntegrationRequestRepository;
import com.originex.partner.domain.model.IntegrationRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class IntegrationRequestPersistenceAdapter implements IntegrationRequestRepository {

    private final IntegrationRequestJpaRepository jpaRepository;

    public IntegrationRequestPersistenceAdapter(IntegrationRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public IntegrationRequest save(IntegrationRequest request) {
        IntegrationRequestJpaEntity entity = IntegrationRequestJpaEntity.fromDomain(request);
        IntegrationRequestJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<IntegrationRequest> findLatestValidCache(UUID tenantId, IntegrationRequest.PartnerType type, String referenceId) {
        return jpaRepository.findValidCache(tenantId, type, referenceId, Instant.now())
                .stream()
                .findFirst()
                .map(IntegrationRequestJpaEntity::toDomain);
    }
}
