package com.originex.lms.domain.service;

import com.originex.common.money.Money;
import com.originex.lms.domain.model.Loan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InterestAccrualCalculator — Actual/365 Fixed daily accrual")
class InterestAccrualCalculatorTest {

    private static final LocalDate LAST = LocalDate.of(2026, 7, 1);

    /** Active loan, ₹5,00,000 outstanding principal, 12.5% p.a. (stored as a percentage). */
    private Loan activeLoan() {
        Loan loan = Loan.createFromApplication(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN",
                Money.of("500000", "INR"),
                new BigDecimal("12.5"), "FIXED",
                24, Money.of("23536.74", "INR"));
        loan.initiateDisbursement(Money.of("500000", "INR"), "ACC-1");
        loan.confirmDisbursement(loan.getDisbursements().get(0).getDisbursementId(), "PAY-1");
        loan.setLastAccrualDate(LAST); // pin the baseline for deterministic assertions
        return loan;
    }

    @Test
    @DisplayName("one day accrual: 500000 × 12.5% / 365 = 171.2329")
    void oneDayAccrual() {
        Money accrued = InterestAccrualCalculator.accrualFor(activeLoan(), LAST.plusDays(1));
        // 500000 × 0.125 / 365 = 62500 / 365 = 171.23287... → 171.2329
        assertThat(accrued.getAmount()).isEqualByComparingTo("171.2329");
    }

    @Test
    @DisplayName("missed days accrue the whole gap: 5 days = 856.1644")
    void missedDaysGap() {
        Money accrued = InterestAccrualCalculator.accrualFor(activeLoan(), LAST.plusDays(5));
        // 62500 × 5 / 365 = 856.16438... → 856.1644
        assertThat(accrued.getAmount()).isEqualByComparingTo("856.1644");
    }

    @Test
    @DisplayName("zero outstanding principal accrues nothing")
    void zeroPrincipal() {
        Loan loan = activeLoan();
        loan.setOutstandingPrincipal(Money.zero("INR"));
        Money accrued = InterestAccrualCalculator.accrualFor(loan, LAST.plusDays(1));
        assertThat(accrued.isZero()).isTrue();
    }

    @Test
    @DisplayName("same-day run (asOf == lastAccrualDate) accrues nothing — duplicate-run guard")
    void sameDayReturnsZero() {
        Money accrued = InterestAccrualCalculator.accrualFor(activeLoan(), LAST);
        assertThat(accrued.isZero()).isTrue();
    }

    @Test
    @DisplayName("asOf before lastAccrualDate accrues nothing (defensive)")
    void asOfBeforeLastReturnsZero() {
        Money accrued = InterestAccrualCalculator.accrualFor(activeLoan(), LAST.minusDays(1));
        assertThat(accrued.isZero()).isTrue();
    }

    @Test
    @DisplayName("null lastAccrualDate accrues nothing (baseline established, no back-dating)")
    void nullLastAccrualDateReturnsZero() {
        Loan loan = activeLoan();
        loan.setLastAccrualDate(null);
        Money accrued = InterestAccrualCalculator.accrualFor(loan, LocalDate.of(2026, 7, 10));
        assertThat(accrued.isZero()).isTrue();
    }

    @Test
    @DisplayName("rounding: 100000 × 10% / 365 for 1 day = 27.3973 (HALF_EVEN, scale 4)")
    void roundingToScaleFour() {
        Loan loan = Loan.createFromApplication(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PL",
                Money.of("100000", "INR"),
                new BigDecimal("10"), "FIXED",
                12, Money.of("8792", "INR"));
        loan.initiateDisbursement(Money.of("100000", "INR"), "ACC-2");
        loan.confirmDisbursement(loan.getDisbursements().get(0).getDisbursementId(), "PAY-2");
        loan.setLastAccrualDate(LAST);

        Money accrued = InterestAccrualCalculator.accrualFor(loan, LAST.plusDays(1));
        // 100000 × 0.10 / 365 = 10000 / 365 = 27.39726... → 27.3973
        assertThat(accrued.getAmount()).isEqualByComparingTo("27.3973");
    }
}
