package com.originex.template.application.port.out;

import com.originex.template.domain.model.Sample;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — defines persistence operations required by the application layer.
 * Implemented by driven adapters (JPA repository, etc.).
 */
public interface SampleRepository {

    Sample save(Sample sample);

    Optional<Sample> findById(UUID tenantId, UUID sampleId);

    boolean existsByName(UUID tenantId, String name);
}
