package com.originex.template.application.port.in;

import com.originex.template.domain.model.Sample;

import java.util.UUID;

/**
 * Inbound port — defines use cases available to driving adapters (REST, gRPC, Kafka).
 * This interface belongs to the application layer and is implemented by application services.
 */
public interface SampleUseCase {

    Sample createSample(CreateSampleCommand command);

    Sample getSample(UUID tenantId, UUID sampleId);

    Sample updateSample(UpdateSampleCommand command);

    void deactivateSample(UUID tenantId, UUID sampleId);

    // ─── Commands (immutable request objects) ───

    record CreateSampleCommand(
            UUID tenantId,
            String name,
            String description,
            String amount,
            String currency
    ) {}

    record UpdateSampleCommand(
            UUID tenantId,
            UUID sampleId,
            String name,
            String description,
            long expectedVersion
    ) {}
}
