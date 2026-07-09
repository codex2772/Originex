package com.originex.partner.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.partner.application.port.in.PartnerIntegrationUseCase;
import com.originex.partner.application.port.in.PartnerIntegrationUseCase.*;
import com.originex.partner.domain.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/partner")
public class PartnerIntegrationController {

    private final PartnerIntegrationUseCase useCase;

    public PartnerIntegrationController(PartnerIntegrationUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/credit-bureau/pull")
    public ResponseEntity<BureauReport> pullCreditReport(@Valid @RequestBody PullBureauRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        BureauReport report = useCase.pullCreditReport(new PullCreditReportCommand(
                tenantId, request.referenceId(), request.preferredBureau(),
                request.panNumber(), request.fullName(), request.dateOfBirth(),
                request.phone(), request.consentArtifactId()
        ));

        return ResponseEntity.ok(report);
    }

    @PostMapping("/aadhaar/verify")
    public ResponseEntity<AadhaarVerificationResult> verifyAadhaar(@Valid @RequestBody VerifyAadhaarRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        AadhaarVerificationResult result = useCase.verifyAadhaar(new VerifyAadhaarCommand(
                tenantId, request.referenceId(), request.aadhaarNumberOrVid(),
                request.consentArtifactId(), request.otpReference()
        ));

        return ResponseEntity.ok(result);
    }

    @PostMapping("/pan/verify")
    public ResponseEntity<PanVerificationResult> verifyPan(@Valid @RequestBody VerifyPanRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        PanVerificationResult result = useCase.verifyPan(new VerifyPanCommand(
                tenantId, request.referenceId(), request.panNumber(),
                request.fullName(), request.dateOfBirth()
        ));

        return ResponseEntity.ok(result);
    }

    @PostMapping("/bank-account/verify")
    public ResponseEntity<BankAccountVerificationResult> verifyBankAccount(@Valid @RequestBody VerifyBankAccountRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        BankAccountVerificationResult result = useCase.verifyBankAccount(new VerifyBankAccountCommand(
                tenantId, request.referenceId(), request.accountNumber(),
                request.ifscCode(), request.expectedAccountHolderName()
        ));

        return ResponseEntity.ok(result);
    }

    // ─── Request DTOs ───

    record PullBureauRequest(
            @NotBlank String referenceId,
            String preferredBureau,
            @NotBlank String panNumber,
            @NotBlank String fullName,
            String dateOfBirth,
            String phone,
            @NotBlank String consentArtifactId
    ) {}

    record VerifyAadhaarRequest(
            @NotBlank String referenceId,
            @NotBlank String aadhaarNumberOrVid,
            @NotBlank String consentArtifactId,
            String otpReference
    ) {}

    record VerifyPanRequest(
            @NotBlank String referenceId,
            @NotBlank String panNumber,
            @NotBlank String fullName,
            String dateOfBirth
    ) {}

    record VerifyBankAccountRequest(
            @NotBlank String referenceId,
            @NotBlank String accountNumber,
            @NotBlank String ifscCode,
            @NotBlank String expectedAccountHolderName
    ) {}
}
