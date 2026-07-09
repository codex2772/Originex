package com.originex.lms.domain.service;

import com.originex.common.money.Money;
import com.originex.lms.domain.model.Loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Interest Accrual — computes simple daily interest accrued on a loan's
 * outstanding principal between its last accrual date and a given "as-of" date.
 *
 * <p>Pure domain calculation (no side effects, no persistence) — mirrors
 * {@link ScheduleGenerator}. The caller applies the result via
 * {@link Loan#accrueInterest(Money)} and advances the loan's last-accrual date.
 *
 * <p><b>Day-count convention (v1): Actual/365 Fixed.</b> daily rate =
 * annualRate / 365; accrued = outstandingPrincipal × dailyRate × elapsedDays.
 * A configurable {@code DayCountConvention} abstraction (Actual/360, 30/360,
 * product-specific) is intentionally deferred until product configuration
 * exists — do not hardcode a second convention here; introduce the abstraction
 * when a second one is genuinely required.
 *
 * <p><b>Rate units:</b> {@link Loan#getInterestRate()} holds the annual rate as
 * a <b>percentage</b> (e.g. {@code 12.5} = 12.5% p.a.), matching the convention
 * used by {@code LoanApplicationServiceImpl.createLoan}, which applies
 * {@code movePointLeft(2)} before generating the schedule. This calculator uses
 * the identical basis (÷100 then ÷365) so daily accrual and the contractual EMI
 * schedule share one rate interpretation.
 *
 * <p><b>NPA / non-active loans:</b> this calculator does not inspect loan
 * status — the accrual scheduler only invokes it for {@code ACTIVE} loans (via
 * its eligibility query), and {@link Loan#accrueInterest} enforces the status
 * guard. NPA interest-suspense treatment is deferred (see
 * {@link Loan#accrueInterest}).
 */
public final class InterestAccrualCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365"); // Actual/365 Fixed (v1)

    private InterestAccrualCalculator() {}

    /**
     * Interest accrued from {@code loan.lastAccrualDate} (exclusive) to
     * {@code asOf} (inclusive of the elapsed days), on the current outstanding
     * principal.
     *
     * <p>Returns zero when: the loan has never been activated
     * ({@code lastAccrualDate == null}); {@code asOf} is not strictly after the
     * last accrual date (no elapsed days, e.g. a same-day duplicate run); or the
     * outstanding principal is non-positive.
     */
    public static Money accrualFor(Loan loan, LocalDate asOf) {
        LocalDate last = loan.getLastAccrualDate();
        Money principal = loan.getOutstandingPrincipal();

        if (last == null || asOf == null || !asOf.isAfter(last)) {
            return Money.zero(loan.getCurrency());
        }
        if (principal == null || !principal.isPositive()) {
            return Money.zero(loan.getCurrency());
        }

        long days = ChronoUnit.DAYS.between(last, asOf);

        // Loan.interestRate is an annual percentage → fraction (÷100) → daily (÷365).
        // This uses the SAME conversion (movePointLeft(2)) that
        // LoanApplicationServiceImpl.createLoan applies before scheduling, so accrual
        // and the EMI schedule share one rate basis — no new convention is introduced.
        //
        // TODO (tech-debt: rate-unit normalization — to be tracked in CLAUDE_ANALYSIS.md §9):
        //   The aggregate stores the rate as a PERCENTAGE while ScheduleGenerator's
        //   parameter is documented as a FRACTION, reconciled ad-hoc via
        //   movePointLeft(2) in the application service. Normalizing the rate unit on
        //   the Loan aggregate is a separate, coordinated change: it touches EMI
        //   calculation, interest accrual, and future repayment/penalty logic, so it
        //   must NOT be done piecemeal here.
        BigDecimal dailyRate = loan.getInterestRate()
                .movePointLeft(2)
                .divide(DAYS_IN_YEAR, MC);
        BigDecimal factor = dailyRate.multiply(BigDecimal.valueOf(days), MC);

        // Money.multiply normalises to Money's scale (4) with HALF_EVEN.
        return principal.multiply(factor);
    }
}
