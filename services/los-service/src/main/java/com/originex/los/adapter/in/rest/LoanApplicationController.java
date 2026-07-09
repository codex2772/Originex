package com.originex.los.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.los.application.port.in.LoanApplicationUseCase;
import com.originex.los.application.port.in.LoanApplicationUseCase.*;
import com.originex.los.domain.model.LoanApplication;
import com.originex.los.domain.model.LoanOffer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/loan-applications")
public class LoanApplicationController {

    private final LoanApplicationUseCase useCase;

    public LoanApplicationController(LoanApplicationUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> submit(@Valid @RequestBody SubmitRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        SubmitApplicationCommand command = new SubmitApplicationCommand(
                tenantId,
                UUID.fromString(request.customerId()),
                request.productCode(),
                request.amount(),
                request.currency(),
                request.tenureMonths(),
                request.purpose(),
                request.channel(),
                request.applicantName(),
                request.applicantPan(),
                request.employmentType(),
                request.monthlyIncome()
        );

        LoanApplication app = useCase.submitApplication(command);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(app.getApplicationId()).toUri();

        return ResponseEntity.accepted()
                .location(location)
                .body(ApplicationResponse.from(app));
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<ApplicationResponse> get(@PathVariable UUID applicationId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        LoanApplication app = useCase.getApplication(tenantId, applicationId);
        return ResponseEntity.ok(ApplicationResponse.from(app));
    }

    @PostMapping("/{applicationId}/documents")
    public ResponseEntity<ApplicationResponse> addDocument(
            @PathVariable UUID applicationId,
            @Valid @RequestBody AddDocumentRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        AddDocumentCommand command = new AddDocumentCommand(
                tenantId, applicationId,
                request.documentType(), request.fileName(), request.storageUrl()
        );

        LoanApplication app = useCase.addDocument(command);
        return ResponseEntity.ok(ApplicationResponse.from(app));
    }

    @PostMapping("/{applicationId}/credit-check")
    public ResponseEntity<ApplicationResponse> initiateCreditCheck(
            @PathVariable UUID applicationId,
            @Valid @RequestBody CreditCheckRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        LoanApplication app = useCase.initiateCreditCheck(tenantId, applicationId, request.consentArtifactId());
        return ResponseEntity.ok(ApplicationResponse.from(app));
    }

    @PostMapping("/{applicationId}/approve")
    public ResponseEntity<ApplicationResponse> approve(
            @PathVariable UUID applicationId,
            @Valid @RequestBody ApproveRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        ApproveCommand command = new ApproveCommand(
                tenantId, applicationId,
                request.sanctionedAmount(), request.interestRate(), request.tenureMonths(),
                request.emi(), request.processingFee(), request.apr(), request.notes()
        );

        LoanApplication app = useCase.approveAndGenerateOffer(command);
        return ResponseEntity.ok(ApplicationResponse.from(app));
    }

    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<ApplicationResponse> reject(
            @PathVariable UUID applicationId,
            @Valid @RequestBody RejectRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        LoanApplication app = useCase.rejectApplication(
                new RejectCommand(tenantId, applicationId, request.reason()));
        return ResponseEntity.ok(ApplicationResponse.from(app));
    }

    @PostMapping("/{applicationId}/offer/accept")
    public ResponseEntity<ApplicationResponse> acceptOffer(@PathVariable UUID applicationId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        LoanApplication app = useCase.acceptOffer(tenantId, applicationId);
        return ResponseEntity.ok(ApplicationResponse.from(app));
    }

    @DeleteMapping("/{applicationId}")
    public ResponseEntity<Void> withdraw(@PathVariable UUID applicationId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        useCase.withdrawApplication(tenantId, applicationId);
        return ResponseEntity.noContent().build();
    }

    // ─── Request DTOs ───

    record SubmitRequest(
            @NotBlank String customerId,
            @NotBlank String productCode,
            @NotBlank String amount,
            String currency,
            @NotNull @Positive Integer tenureMonths,
            String purpose,
            String channel,
            String applicantName,
            String applicantPan,
            String employmentType,
            String monthlyIncome
    ) {}

    record AddDocumentRequest(
            @NotBlank String documentType,
            @NotBlank String fileName,
            @NotBlank String storageUrl
    ) {}

    record CreditCheckRequest(
            @NotBlank String consentArtifactId
    ) {}

    record ApproveRequest(
            @NotBlank String sanctionedAmount,
            @NotBlank String interestRate,
            @NotNull @Positive Integer tenureMonths,
            @NotBlank String emi,
            @NotBlank String processingFee,
            @NotBlank String apr,
            String notes
    ) {}

    record RejectRequest(
            @NotBlank String reason
    ) {}

    // ─── Response DTO ───

    record ApplicationResponse(
            UUID id,
            UUID customerId,
            String productCode,
            String status,
            String requestedAmount,
            String currency,
            int tenureMonths,
            String purpose,
            String applicantName,
            Integer creditScore,
            OfferResponse offer,
            long version,
            String submittedAt,
            String updatedAt
    ) {
        static ApplicationResponse from(LoanApplication app) {
            OfferResponse offerResp = null;
            if (app.getCurrentOffer() != null) {
                LoanOffer o = app.getCurrentOffer();
                offerResp = new OfferResponse(
                        o.getOfferId(),
                        o.getSanctionedAmount().getAmount().toPlainString(),
                        o.getInterestRate().toPlainString(),
                        o.getTenureMonths(),
                        o.getEmi().getAmount().toPlainString(),
                        o.getProcessingFee().getAmount().toPlainString(),
                        o.getApr().toPlainString(),
                        o.getTotalRepayment().getAmount().toPlainString(),
                        o.getExpiresAt().toString()
                );
            }

            return new ApplicationResponse(
                    app.getApplicationId(),
                    app.getCustomerId(),
                    app.getProductCode(),
                    app.getStatus().name(),
                    app.getRequestedAmount().getAmount().toPlainString(),
                    app.getRequestedAmount().getCurrencyCode(),
                    app.getRequestedTenureMonths(),
                    app.getPurpose(),
                    app.getApplicantName(),
                    app.getCreditScore(),
                    offerResp,
                    app.getVersion(),
                    app.getSubmittedAt() != null ? app.getSubmittedAt().toString() : null,
                    app.getUpdatedAt().toString()
            );
        }
    }

    record OfferResponse(
            UUID offerId,
            String sanctionedAmount,
            String interestRate,
            int tenureMonths,
            String emi,
            String processingFee,
            String apr,
            String totalRepayment,
            String expiresAt
    ) {}
}
