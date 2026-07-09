package com.originex.bre.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.bre.application.port.in.EvaluationUseCase;
import com.originex.bre.application.port.in.EvaluationUseCase.EvaluateCommand;
import com.originex.bre.domain.model.EvaluationResult;
import com.originex.bre.domain.model.EvaluationResult.RuleResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/bre")
public class BREController {

    private final EvaluationUseCase evaluationUseCase;

    public BREController(EvaluationUseCase evaluationUseCase) {
        this.evaluationUseCase = evaluationUseCase;
    }

    /**
     * Evaluate a loan application — called by LOS synchronously before submission.
     */
    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationResponse> evaluate(@Valid @RequestBody EvaluateRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        EvaluationResult result = evaluationUseCase.evaluate(new EvaluateCommand(
                tenantId,
                request.applicationId(),
                request.customerId(),
                request.productCode(),
                request.employmentType(),
                request.creditScore(),
                request.bureauName(),
                request.hasWriteOff(),
                request.hasSettlement(),
                request.enquiriesLast6Months(),
                request.activeLoansCount(),
                new BigDecimal(request.existingEmiObligations()),
                new BigDecimal(request.monthlyIncome()),
                request.applicantAgeYears(),
                new BigDecimal(request.requestedAmount()),
                request.requestedTenureMonths(),
                request.currency() != null ? request.currency() : "INR"
        ));

        return ResponseEntity.ok(EvaluationResponse.from(result));
    }

    // ─── Request DTO ───

    record EvaluateRequest(
            @NotBlank String applicationId,
            @NotBlank String customerId,
            @NotBlank String productCode,
            @NotBlank String employmentType,
            int creditScore,
            String bureauName,
            boolean hasWriteOff,
            boolean hasSettlement,
            int enquiriesLast6Months,
            int activeLoansCount,
            @NotBlank String existingEmiObligations,
            @NotBlank String monthlyIncome,
            @Positive int applicantAgeYears,
            @NotBlank String requestedAmount,
            @Positive int requestedTenureMonths,
            String currency
    ) {}

    // ─── Response DTO ───

    record EvaluationResponse(
            String evaluationId,
            String applicationId,
            String decision,
            String riskGrade,
            String summary,
            String approvedAmount,
            String interestRate,
            int approvedTenureMonths,
            String emi,
            String processingFeeRate,
            String apr,
            List<RuleResultDto> ruleResults,
            Instant evaluatedAt
    ) {
        static EvaluationResponse from(EvaluationResult r) {
            return new EvaluationResponse(
                    r.getEvaluationId().toString(),
                    r.getApplicationId(),
                    r.getDecision().name(),
                    r.getRiskGrade(),
                    r.getSummary(),
                    r.getApprovedAmount() != null ? r.getApprovedAmount().toPlainString() : null,
                    r.getInterestRate() != null ? r.getInterestRate().toPlainString() : null,
                    r.getApprovedTenureMonths(),
                    r.getEmi() != null ? r.getEmi().toPlainString() : null,
                    r.getProcessingFeeRate() != null ? r.getProcessingFeeRate().toPlainString() : null,
                    r.getApr() != null ? r.getApr().toPlainString() : null,
                    r.getRuleResults().stream().map(rr -> new RuleResultDto(
                            rr.ruleCode(), rr.ruleType().name(),
                            rr.passed(), rr.failureMessage(), rr.factValue()
                    )).toList(),
                    r.getEvaluatedAt()
            );
        }
    }

    record RuleResultDto(String ruleCode, String ruleType, boolean passed,
                         String failureMessage, String factValue) {}
}
