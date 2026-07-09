package com.originex.template.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.template.application.port.in.SampleUseCase;
import com.originex.template.application.port.in.SampleUseCase.CreateSampleCommand;
import com.originex.template.application.port.in.SampleUseCase.UpdateSampleCommand;
import com.originex.template.domain.model.Sample;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST driving adapter — translates HTTP requests into application use case calls.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>HTTP request/response mapping</li>
 *   <li>Input DTO validation</li>
 *   <li>Delegation to use case port</li>
 *   <li>Response construction</li>
 * </ul>
 *
 * <p>Does NOT contain business logic.
 */
@RestController
@RequestMapping("/v1/samples")
public class SampleController {

    private final SampleUseCase sampleUseCase;

    public SampleController(SampleUseCase sampleUseCase) {
        this.sampleUseCase = sampleUseCase;
    }

    @PostMapping
    public ResponseEntity<SampleResponse> create(@Valid @RequestBody CreateSampleRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        CreateSampleCommand command = new CreateSampleCommand(
                tenantId,
                request.name(),
                request.description(),
                request.amount(),
                request.currency()
        );

        Sample sample = sampleUseCase.createSample(command);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(sample.getSampleId())
                .toUri();

        return ResponseEntity.created(location).body(SampleResponse.from(sample));
    }

    @GetMapping("/{sampleId}")
    public ResponseEntity<SampleResponse> get(@PathVariable UUID sampleId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Sample sample = sampleUseCase.getSample(tenantId, sampleId);
        return ResponseEntity.ok(SampleResponse.from(sample));
    }

    @PutMapping("/{sampleId}")
    public ResponseEntity<SampleResponse> update(
            @PathVariable UUID sampleId,
            @RequestHeader("If-Match") long expectedVersion,
            @Valid @RequestBody UpdateSampleRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        UpdateSampleCommand command = new UpdateSampleCommand(
                tenantId, sampleId, request.name(), request.description(), expectedVersion
        );

        Sample sample = sampleUseCase.updateSample(command);

        return ResponseEntity.ok()
                .header("ETag", String.valueOf(sample.getVersion()))
                .body(SampleResponse.from(sample));
    }

    @DeleteMapping("/{sampleId}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID sampleId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        sampleUseCase.deactivateSample(tenantId, sampleId);
        return ResponseEntity.noContent().build();
    }

    // ─── Request/Response DTOs ───

    record CreateSampleRequest(
            @NotBlank String name,
            String description,
            String amount,
            String currency
    ) {}

    record UpdateSampleRequest(
            @NotBlank String name,
            String description
    ) {}

    record SampleResponse(
            UUID id,
            String name,
            String description,
            String status,
            String amount,
            String currency,
            long version,
            String createdAt,
            String updatedAt
    ) {
        static SampleResponse from(Sample s) {
            return new SampleResponse(
                    s.getSampleId(),
                    s.getName(),
                    s.getDescription(),
                    s.getStatus().name(),
                    s.getAmount() != null ? s.getAmount().getAmount().toPlainString() : null,
                    s.getAmount() != null ? s.getAmount().getCurrencyCode() : null,
                    s.getVersion(),
                    s.getCreatedAt().toString(),
                    s.getUpdatedAt().toString()
            );
        }
    }
}
