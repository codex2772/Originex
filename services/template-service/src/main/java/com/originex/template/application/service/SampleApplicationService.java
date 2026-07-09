package com.originex.template.application.service;

import com.originex.common.money.Money;
import com.originex.template.application.port.in.SampleUseCase;
import com.originex.template.application.port.out.EventPublisher;
import com.originex.template.application.port.out.SampleRepository;
import com.originex.template.domain.exception.SampleNotFoundException;
import com.originex.template.domain.model.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service — orchestrates use cases by coordinating domain objects and ports.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Transaction management</li>
 *   <li>Input validation (application-level)</li>
 *   <li>Coordinating domain objects</li>
 *   <li>Publishing events via outbound port</li>
 * </ul>
 *
 * <p>Does NOT contain business logic — that belongs in the domain model.
 */
@Service
@Transactional
public class SampleApplicationService implements SampleUseCase {

    private static final Logger log = LoggerFactory.getLogger(SampleApplicationService.class);

    private final SampleRepository sampleRepository;
    private final EventPublisher eventPublisher;

    public SampleApplicationService(SampleRepository sampleRepository, EventPublisher eventPublisher) {
        this.sampleRepository = sampleRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Sample createSample(CreateSampleCommand command) {
        log.info("Creating sample: name={}, tenant={}", command.name(), command.tenantId());

        // Application-level validation (not domain invariant)
        if (sampleRepository.existsByName(command.tenantId(), command.name())) {
            throw new IllegalArgumentException("Sample with name '" + command.name() + "' already exists");
        }

        // Construct Money value object if amount provided
        Money amount = null;
        if (command.amount() != null) {
            String currency = command.currency() != null ? command.currency() : "INR";
            amount = Money.of(command.amount(), currency);
        }

        // Domain object creation (invariants enforced inside)
        Sample sample = Sample.create(command.tenantId(), command.name(), command.description(), amount);

        // Persist via outbound port
        Sample saved = sampleRepository.save(sample);

        log.info("Sample created: id={}", saved.getSampleId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Sample getSample(UUID tenantId, UUID sampleId) {
        return sampleRepository.findById(tenantId, sampleId)
                .orElseThrow(() -> new SampleNotFoundException(sampleId));
    }

    @Override
    public Sample updateSample(UpdateSampleCommand command) {
        Sample sample = sampleRepository.findById(command.tenantId(), command.sampleId())
                .orElseThrow(() -> new SampleNotFoundException(command.sampleId()));

        // Optimistic locking check
        if (sample.getVersion() != command.expectedVersion()) {
            throw new IllegalStateException(
                    "Version conflict: expected " + command.expectedVersion()
                            + " but found " + sample.getVersion());
        }

        // Domain behavior (invariants enforced inside domain model)
        sample.updateDetails(command.name(), command.description());

        return sampleRepository.save(sample);
    }

    @Override
    public void deactivateSample(UUID tenantId, UUID sampleId) {
        Sample sample = sampleRepository.findById(tenantId, sampleId)
                .orElseThrow(() -> new SampleNotFoundException(sampleId));

        // Domain behavior
        sample.deactivate();

        sampleRepository.save(sample);
        log.info("Sample deactivated: id={}", sampleId);
    }
}
