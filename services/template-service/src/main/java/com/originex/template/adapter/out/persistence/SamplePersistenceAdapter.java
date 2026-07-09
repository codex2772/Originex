package com.originex.template.adapter.out.persistence;

import com.originex.template.application.port.out.SampleRepository;
import com.originex.template.domain.model.Sample;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter — implements the outbound port (SampleRepository)
 * using Spring Data JPA as the underlying technology.
 *
 * <p>This adapter:
 * <ul>
 *   <li>Converts domain objects ↔ JPA entities</li>
 *   <li>Delegates to Spring Data JPA repository</li>
 *   <li>Is the ONLY place in the codebase that knows about JPA entities</li>
 * </ul>
 */
@Component
public class SamplePersistenceAdapter implements SampleRepository {

    private final SampleJpaRepository jpaRepository;

    public SamplePersistenceAdapter(SampleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Sample save(Sample sample) {
        SampleJpaEntity entity = SampleJpaEntity.fromDomain(sample);
        SampleJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Sample> findById(UUID tenantId, UUID sampleId) {
        return jpaRepository.findByTenantAndId(tenantId, sampleId)
                .map(SampleJpaEntity::toDomain);
    }

    @Override
    public boolean existsByName(UUID tenantId, String name) {
        return jpaRepository.existsByTenantIdAndName(tenantId, name);
    }
}
