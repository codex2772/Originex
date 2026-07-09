package com.originex.bre.application.port.in;

import com.originex.bre.domain.model.EvaluationResult;

import java.math.BigDecimal;
import java.util.UUID;

public interface EvaluationUseCase {

    /**
     * Evaluate a loan application against BRE rules.
     * Called synchronously by LOS before accepting an application.
     */
    EvaluationResult evaluate(EvaluateCommand command);

    record EvaluateCommand(
            UUID tenantId,
            String applicationId,
            String customerId,
            String productCode,
            String employmentType,

            // Bureau facts
            int creditScore,
            String bureauName,
            boolean hasWriteOff,
            boolean hasSettlement,
            int enquiriesLast6Months,
            int activeLoansCount,
            BigDecimal existingEmiObligations,

            // Income facts
            BigDecimal monthlyIncome,
            int applicantAgeYears,

            // Loan request
            BigDecimal requestedAmount,
            int requestedTenureMonths,
            String currency
    ) {}
}
