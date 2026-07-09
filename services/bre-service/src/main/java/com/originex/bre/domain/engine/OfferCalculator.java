package com.originex.bre.domain.engine;

import com.originex.bre.domain.model.EvaluationFacts;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * OfferCalculator — computes loan offer parameters after BRE approval.
 *
 * <p>All calculations use BigDecimal with HALF_EVEN (Banker's rounding) — never float/double.
 *
 * <p>EMI formula (reducing balance):
 * EMI = P × r × (1+r)^n / ((1+r)^n - 1)
 * where P = principal, r = monthly rate, n = tenure months
 *
 * <p>Interest rate is determined by risk grade:
 * <ul>
 *   <li>LOW risk → base rate</li>
 *   <li>MEDIUM risk → base rate + spread</li>
 *   <li>HIGH risk → not approved</li>
 * </ul>
 */
@Service
public class OfferCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE  = new BigDecimal("12");
    private static final MathContext MC10   = new MathContext(10, RoundingMode.HALF_EVEN);

    /**
     * Determine annual interest rate based on credit score and risk grade.
     */
    public BigDecimal determineRate(int creditScore, String riskGrade, String productCode) {
        // Base rates by credit score band (configurable per tenant in production)
        BigDecimal base = creditScore >= 800 ? new BigDecimal("10.50")
                        : creditScore >= 750 ? new BigDecimal("11.25")
                        : creditScore >= 700 ? new BigDecimal("12.50")
                        : creditScore >= 650 ? new BigDecimal("14.00")
                        :                      new BigDecimal("16.00");

        // Risk grade spread
        BigDecimal spread = switch (riskGrade) {
            case "LOW"    -> BigDecimal.ZERO;
            case "MEDIUM" -> new BigDecimal("1.50");
            default       -> new BigDecimal("2.50");
        };

        return base.add(spread).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Determine approved loan amount — may be less than requested if FOIR is high.
     * Max loan = income multiplier × annual income.
     */
    public BigDecimal determineApprovedAmount(EvaluationFacts facts, BigDecimal annualRate) {
        BigDecimal requested = facts.requestedAmount();

        // Max EMI capacity = (maxFoirThreshold × monthlyIncome) - existingEMIs
        BigDecimal maxFoir = new BigDecimal("0.50"); // 50% FOIR max
        BigDecimal maxEmiCapacity = facts.monthlyIncome().multiply(maxFoir)
                .subtract(facts.existingEmiObligations())
                .max(BigDecimal.ZERO);

        if (maxEmiCapacity.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Max principal that fits within EMI capacity
        BigDecimal monthlyRate = annualRate.divide(HUNDRED, 8, RoundingMode.HALF_EVEN)
                .divide(TWELVE, 8, RoundingMode.HALF_EVEN);
        BigDecimal n = new BigDecimal(facts.requestedTenureMonths());

        BigDecimal maxPrincipal = calculateMaxPrincipal(maxEmiCapacity, monthlyRate, n);

        // Return the lesser of requested and calculated max
        return requested.min(maxPrincipal).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Calculate EMI using reducing balance formula.
     * EMI = P × r × (1+r)^n / ((1+r)^n - 1)
     */
    public BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRate, int tenureMonths) {
        if (principal.compareTo(BigDecimal.ZERO) == 0 || tenureMonths == 0) return BigDecimal.ZERO;

        BigDecimal r = annualRate.divide(HUNDRED, 8, RoundingMode.HALF_EVEN)
                                 .divide(TWELVE, 8, RoundingMode.HALF_EVEN);
        BigDecimal n = new BigDecimal(tenureMonths);

        // (1+r)^n
        BigDecimal onePlusR = BigDecimal.ONE.add(r);
        BigDecimal onePlusRPowN = onePlusR.pow(tenureMonths, MC10);

        // P × r × (1+r)^n
        BigDecimal numerator = principal.multiply(r).multiply(onePlusRPowN);

        // (1+r)^n - 1
        BigDecimal denominator = onePlusRPowN.subtract(BigDecimal.ONE);

        if (denominator.compareTo(BigDecimal.ZERO) == 0) return principal.divide(n, 2, RoundingMode.HALF_EVEN);

        return numerator.divide(denominator, 2, RoundingMode.HALF_EVEN);
    }

    /**
     * Calculate APR (includes processing fee amortised over tenure).
     */
    public BigDecimal calculateApr(BigDecimal principal, BigDecimal annualRate,
                                    BigDecimal processingFeeRate, int tenureMonths) {
        BigDecimal processingFee = principal.multiply(processingFeeRate)
                .divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        BigDecimal effectivePrincipal = principal.subtract(processingFee);

        // Simple APR approximation: annualRate + (processingFee/principal / tenureYears × 12)
        if (effectivePrincipal.compareTo(BigDecimal.ZERO) <= 0) return annualRate;

        BigDecimal tenureYears = new BigDecimal(tenureMonths).divide(TWELVE, 4, RoundingMode.HALF_EVEN);
        BigDecimal feeAnnualised = processingFeeRate.divide(tenureYears, 4, RoundingMode.HALF_EVEN);

        return annualRate.add(feeAnnualised).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Processing fee rate by risk grade.
     */
    public BigDecimal determineProcessingFeeRate(String riskGrade) {
        return switch (riskGrade) {
            case "LOW"    -> new BigDecimal("1.00");
            case "MEDIUM" -> new BigDecimal("1.50");
            default       -> new BigDecimal("2.00");
        };
    }

    // ─── Private helpers ───

    /** Max principal P = EMI × ((1+r)^n - 1) / (r × (1+r)^n) */
    private BigDecimal calculateMaxPrincipal(BigDecimal emi, BigDecimal monthlyRate, BigDecimal n) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return emi.multiply(n);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal onePlusRPowN = onePlusR.pow(n.intValue(), MC10);
        BigDecimal numerator = emi.multiply(onePlusRPowN.subtract(BigDecimal.ONE));
        BigDecimal denominator = monthlyRate.multiply(onePlusRPowN);
        return numerator.divide(denominator, 2, RoundingMode.HALF_EVEN);
    }
}
