package com.originex.lms.domain.service;

import com.originex.common.money.Money;
import com.originex.lms.domain.model.Installment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Schedule Generation — calculates EMI amortization using reducing balance method.
 *
 * <p>EMI Formula: P × r × (1+r)^n / ((1+r)^n - 1)
 * <p>All calculations use BigDecimal with HALF_EVEN rounding (deterministic).
 */
public class ScheduleGenerator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_EVEN;

    /**
     * Generate amortization schedule.
     *
     * @param principal       Loan principal
     * @param annualRate      Annual interest rate (e.g., 0.12 for 12%)
     * @param tenureMonths    Loan tenure in months
     * @param firstDueDate    First EMI due date
     * @return List of installments (one per month)
     */
    public static List<Installment> generate(Money principal, BigDecimal annualRate,
                                             int tenureMonths, LocalDate firstDueDate) {
        String currency = principal.getCurrencyCode();
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(12), 10, RM);
        Money emi = calculateEmi(principal, monthlyRate, tenureMonths);

        List<Installment> installments = new ArrayList<>();
        Money remainingPrincipal = principal;

        for (int i = 1; i <= tenureMonths; i++) {
            // Interest for this month = remainingPrincipal × monthlyRate
            Money interestForMonth = remainingPrincipal.multiply(monthlyRate);

            // Principal for this month = EMI - interest
            Money principalForMonth;
            if (i == tenureMonths) {
                // Last installment: settle all remaining principal (avoid rounding residue)
                principalForMonth = remainingPrincipal;
                // Adjust EMI for last installment
                emi = principalForMonth.add(interestForMonth);
            } else {
                principalForMonth = emi.subtract(interestForMonth);
            }

            LocalDate dueDate = firstDueDate.plusMonths(i - 1);
            Installment inst = Installment.create(i, dueDate, principalForMonth, interestForMonth);
            installments.add(inst);

            remainingPrincipal = remainingPrincipal.subtract(principalForMonth);
        }

        return installments;
    }

    /**
     * Calculate EMI: P × r × (1+r)^n / ((1+r)^n - 1)
     */
    public static Money calculateEmi(Money principal, BigDecimal monthlyRate, int tenureMonths) {
        BigDecimal P = principal.getAmount();

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return Money.of(P.divide(BigDecimal.valueOf(tenureMonths), SCALE, RM),
                    principal.getCurrencyCode());
        }

        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal power = onePlusR.pow(tenureMonths, MC);

        BigDecimal numerator = P.multiply(monthlyRate, MC).multiply(power, MC);
        BigDecimal denominator = power.subtract(BigDecimal.ONE);

        BigDecimal emi = numerator.divide(denominator, SCALE, RM);
        return Money.of(emi, principal.getCurrencyCode());
    }
}
