package com.originex.lms.domain.service;

import com.originex.common.money.Money;
import com.originex.lms.domain.model.Installment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduleGenerator — EMI Amortization")
class ScheduleGeneratorTest {

    @Test
    @DisplayName("Should generate correct number of installments")
    void shouldGenerateCorrectInstallmentCount() {
        Money principal = Money.of("500000", "INR");
        BigDecimal annualRate = new BigDecimal("0.12"); // 12%
        int tenure = 24;
        LocalDate firstDue = LocalDate.of(2026, 8, 5);

        List<Installment> schedule = ScheduleGenerator.generate(principal, annualRate, tenure, firstDue);

        assertThat(schedule).hasSize(24);
        assertThat(schedule.get(0).getInstallmentNumber()).isEqualTo(1);
        assertThat(schedule.get(23).getInstallmentNumber()).isEqualTo(24);
    }

    @Test
    @DisplayName("Total principal across all installments should equal loan amount")
    void totalPrincipalShouldEqualLoanAmount() {
        Money principal = Money.of("500000", "INR");
        BigDecimal annualRate = new BigDecimal("0.12");
        int tenure = 24;
        LocalDate firstDue = LocalDate.of(2026, 8, 5);

        List<Installment> schedule = ScheduleGenerator.generate(principal, annualRate, tenure, firstDue);

        Money totalPrincipal = schedule.stream()
                .map(Installment::getPrincipalDue)
                .reduce(Money.zero("INR"), Money::add);

        // Total principal repaid must equal original principal (no rounding leakage)
        assertThat(totalPrincipal.getAmount()).isEqualByComparingTo("500000.0000");
    }

    @Test
    @DisplayName("Due dates should be monthly")
    void dueDatesShouldBeMonthly() {
        Money principal = Money.of("100000", "INR");
        BigDecimal annualRate = new BigDecimal("0.10");
        int tenure = 12;
        LocalDate firstDue = LocalDate.of(2026, 9, 1);

        List<Installment> schedule = ScheduleGenerator.generate(principal, annualRate, tenure, firstDue);

        assertThat(schedule.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(schedule.get(1).getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(schedule.get(11).getDueDate()).isEqualTo(LocalDate.of(2027, 8, 1));
    }

    @Test
    @DisplayName("Interest portion should decrease over time (reducing balance)")
    void interestShouldDecreaseOverTime() {
        Money principal = Money.of("1000000", "INR");
        BigDecimal annualRate = new BigDecimal("0.12");
        int tenure = 60;
        LocalDate firstDue = LocalDate.of(2026, 8, 5);

        List<Installment> schedule = ScheduleGenerator.generate(principal, annualRate, tenure, firstDue);

        // First installment should have higher interest than the last
        Money firstInterest = schedule.get(0).getInterestDue();
        Money lastInterest = schedule.get(58).getInterestDue(); // second-to-last (last is adjusted)

        assertThat(firstInterest.isGreaterThan(lastInterest)).isTrue();
    }

    @Test
    @DisplayName("EMI calculation should match standard formula")
    void emiCalculationShouldBeCorrect() {
        Money principal = Money.of("500000", "INR");
        BigDecimal monthlyRate = new BigDecimal("0.12").divide(BigDecimal.valueOf(12), 10, java.math.RoundingMode.HALF_EVEN);
        int tenure = 24;

        Money emi = ScheduleGenerator.calculateEmi(principal, monthlyRate, tenure);

        // Expected EMI for 5L at 12% for 24 months ≈ ₹23,536.74
        assertThat(emi.getAmount()).isBetween(
                new BigDecimal("23530.0000"),
                new BigDecimal("23540.0000")
        );
    }

    @Test
    @DisplayName("Zero interest rate should produce flat principal installments")
    void zeroInterestShouldProduceFlatPrincipal() {
        Money principal = Money.of("120000", "INR");
        BigDecimal annualRate = BigDecimal.ZERO;
        int tenure = 12;
        LocalDate firstDue = LocalDate.of(2026, 8, 1);

        List<Installment> schedule = ScheduleGenerator.generate(principal, annualRate, tenure, firstDue);

        // Each installment principal should be exactly 10000
        for (Installment inst : schedule) {
            assertThat(inst.getPrincipalDue().getAmount()).isEqualByComparingTo("10000.0000");
            assertThat(inst.getInterestDue().isZero()).isTrue();
        }
    }
}
