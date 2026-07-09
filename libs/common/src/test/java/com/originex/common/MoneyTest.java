package com.originex.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money Value Object")
class MoneyTest {

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        void shouldCreateFromStringAmount() {
            Money money = Money.of("500000.00", "INR");
            assertThat(money.getAmount()).isEqualByComparingTo("500000.0000");
            assertThat(money.getCurrencyCode()).isEqualTo("INR");
        }

        @Test
        void shouldCreateFromBigDecimal() {
            Money money = Money.of(new BigDecimal("1234.56"), "INR");
            assertThat(money.getAmount()).isEqualByComparingTo("1234.5600");
        }

        @Test
        void shouldCreateZero() {
            Money zero = Money.zero("INR");
            assertThat(zero.isZero()).isTrue();
            assertThat(zero.getAmount()).isEqualByComparingTo("0.0000");
        }

        @Test
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> Money.of((BigDecimal) null, "INR"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullCurrency() {
            assertThatThrownBy(() -> Money.of("100", (String) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Arithmetic")
    class Arithmetic {

        @Test
        void shouldAdd() {
            Money a = Money.of("100.50", "INR");
            Money b = Money.of("200.75", "INR");
            Money result = a.add(b);
            assertThat(result.getAmount()).isEqualByComparingTo("301.2500");
        }

        @Test
        void shouldSubtract() {
            Money a = Money.of("500.00", "INR");
            Money b = Money.of("123.45", "INR");
            Money result = a.subtract(b);
            assertThat(result.getAmount()).isEqualByComparingTo("376.5500");
        }

        @Test
        void shouldMultiply() {
            Money money = Money.of("1000.00", "INR");
            Money result = money.multiply(new BigDecimal("0.12"));
            assertThat(result.getAmount()).isEqualByComparingTo("120.0000");
        }

        @Test
        void shouldDivide() {
            Money money = Money.of("1000.00", "INR");
            Money result = money.divide(3);
            assertThat(result.getAmount()).isEqualByComparingTo("333.3333");
        }

        @Test
        void shouldRejectDivisionByZero() {
            Money money = Money.of("100", "INR");
            assertThatThrownBy(() -> money.divide(BigDecimal.ZERO))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void shouldRejectDifferentCurrencyAdd() {
            Money inr = Money.of("100", "INR");
            Money usd = Money.of("100", "USD");
            assertThatThrownBy(() -> inr.add(usd))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("Comparison")
    class Comparison {

        @Test
        void shouldIdentifyPositive() {
            assertThat(Money.of("100", "INR").isPositive()).isTrue();
            assertThat(Money.of("-100", "INR").isPositive()).isFalse();
            assertThat(Money.of("0", "INR").isPositive()).isFalse();
        }

        @Test
        void shouldCompare() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("200.00", "INR");
            assertThat(a.isLessThan(b)).isTrue();
            assertThat(b.isGreaterThan(a)).isTrue();
        }

        @Test
        void shouldFindMin() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("200.00", "INR");
            assertThat(a.min(b)).isEqualTo(a);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        void shouldBeEqualForSameValue() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("100.0000", "INR");
            assertThat(a).isEqualTo(b);
        }

        @Test
        void shouldNotBeEqualForDifferentCurrency() {
            Money a = Money.of("100.00", "INR");
            Money b = Money.of("100.00", "USD");
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("Financial Precision")
    class FinancialPrecision {

        @ParameterizedTest
        @CsvSource({
                "450000, 0.12, 365, 147.9452",    // Standard daily accrual
                "1000000, 0.15, 365, 410.9589",   // Larger principal
                "100000, 0.09, 360, 25.0000",     // 30/360 convention
        })
        void shouldCalculateDailyInterestCorrectly(
                String principal, String annualRate, int daysInYear, String expected) {

            Money principalMoney = Money.of(principal, "INR");
            BigDecimal rate = new BigDecimal(annualRate);
            BigDecimal dailyRate = rate.divide(BigDecimal.valueOf(daysInYear), 10, java.math.RoundingMode.HALF_EVEN);
            Money dailyInterest = principalMoney.multiply(dailyRate);

            assertThat(dailyInterest.getAmount()).isEqualByComparingTo(expected);
        }

        @Test
        void shouldBeDeterministic() {
            // Same calculation run multiple times must produce identical result
            Money principal = Money.of("450000", "INR");
            BigDecimal rate = new BigDecimal("0.12").divide(BigDecimal.valueOf(365), 10, java.math.RoundingMode.HALF_EVEN);

            Money result1 = principal.multiply(rate);
            Money result2 = principal.multiply(rate);
            Money result3 = principal.multiply(rate);

            assertThat(result1).isEqualTo(result2);
            assertThat(result2).isEqualTo(result3);
        }
    }
}
