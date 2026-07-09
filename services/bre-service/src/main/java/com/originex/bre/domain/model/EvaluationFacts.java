package com.originex.bre.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * EvaluationFacts — the set of facts extracted from the loan application
 * and customer profile that BRE rules are evaluated against.
 *
 * <p>All monetary values are in the native currency of the application.
 * All BigDecimal values use the same precision as the Money value object.
 */
public record EvaluationFacts(

        // ─── Identifiers ───
        UUID tenantId,
        String applicationId,
        String customerId,
        String productCode,

        // ─── Credit Bureau Facts ───
        int creditScore,                    // 0 if no bureau hit (thin file)
        String bureauName,
        boolean hasWriteOff,
        boolean hasSettlement,
        int enquiriesLast6Months,
        int activeLoansCount,
        BigDecimal existingEmiObligations,  // Total existing EMI per month

        // ─── Income Facts ───
        String employmentType,              // SALARIED, SELF_EMPLOYED, PENSIONER
        BigDecimal monthlyIncome,
        BigDecimal annualIncome,

        // ─── Age Facts ───
        int applicantAgeYears,
        int ageAtLoanMaturity,              // applicantAge + tenureYears

        // ─── Loan Request Facts ───
        BigDecimal requestedAmount,
        int requestedTenureMonths,
        String currency,

        // ─── Derived Facts (computed before evaluation) ───
        BigDecimal foir,                    // (existingEmi + proposedEmi) / monthlyIncome
        BigDecimal loanToIncome,            // requestedAmount / annualIncome
        BigDecimal proposedEmi              // Calculated EMI for requested amount/rate/tenure

) {
    /**
     * Factory that computes derived facts automatically.
     */
    public static EvaluationFacts of(UUID tenantId, String applicationId, String customerId,
                                      String productCode, int creditScore, String bureauName,
                                      boolean hasWriteOff, boolean hasSettlement,
                                      int enquiriesLast6Months, int activeLoansCount,
                                      BigDecimal existingEmiObligations, String employmentType,
                                      BigDecimal monthlyIncome, int applicantAgeYears,
                                      int tenureMonths, BigDecimal requestedAmount,
                                      BigDecimal proposedEmi, String currency) {

        BigDecimal annualIncome = monthlyIncome.multiply(new BigDecimal("12"));
        BigDecimal ageAtMaturity = new BigDecimal(applicantAgeYears)
                .add(new BigDecimal(tenureMonths).divide(new BigDecimal("12"), 0, java.math.RoundingMode.CEILING));

        BigDecimal foir = BigDecimal.ZERO;
        if (monthlyIncome.compareTo(BigDecimal.ZERO) > 0) {
            foir = (existingEmiObligations.add(proposedEmi))
                    .divide(monthlyIncome, 4, java.math.RoundingMode.HALF_EVEN);
        }

        BigDecimal loanToIncome = BigDecimal.ZERO;
        if (annualIncome.compareTo(BigDecimal.ZERO) > 0) {
            loanToIncome = requestedAmount.divide(annualIncome, 4, java.math.RoundingMode.HALF_EVEN);
        }

        return new EvaluationFacts(
                tenantId, applicationId, customerId, productCode,
                creditScore, bureauName, hasWriteOff, hasSettlement,
                enquiriesLast6Months, activeLoansCount, existingEmiObligations,
                employmentType, monthlyIncome, annualIncome,
                applicantAgeYears, ageAtMaturity.intValue(),
                requestedAmount, tenureMonths, currency,
                foir, loanToIncome, proposedEmi
        );
    }
}
