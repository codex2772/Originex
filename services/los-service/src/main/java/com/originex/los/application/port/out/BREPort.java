package com.originex.los.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound port — BRE eligibility evaluation.
 * LOS calls this synchronously before accepting a loan application.
 * The adapter calls the BRE Service REST API.
 */
public interface BREPort {

    BREResult evaluate(BRERequest request);

    record BRERequest(
            UUID tenantId,
            String applicationId,
            String customerId,
            String productCode,
            String employmentType,
            int creditScore,
            String bureauName,
            boolean hasWriteOff,
            boolean hasSettlement,
            int enquiriesLast6Months,
            int activeLoansCount,
            BigDecimal existingEmiObligations,
            BigDecimal monthlyIncome,
            int applicantAgeYears,
            BigDecimal requestedAmount,
            int requestedTenureMonths,
            String currency
    ) {}

    record BREResult(
            String evaluationId,
            String decision,           // APPROVED, REJECTED, REFER_TO_UNDERWRITER
            String riskGrade,
            String summary,
            BigDecimal approvedAmount,
            BigDecimal interestRate,
            int approvedTenureMonths,
            BigDecimal emi,
            BigDecimal processingFeeRate,
            BigDecimal apr
    ) {
        public boolean isApproved()  { return "APPROVED".equals(decision); }
        public boolean isRejected()  { return "REJECTED".equals(decision); }
        public boolean isReferred()  { return "REFER_TO_UNDERWRITER".equals(decision); }
    }
}
