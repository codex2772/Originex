package com.originex.common.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary value object.
 *
 * <p>Rules:
 * <ul>
 *   <li>ALWAYS uses BigDecimal — never float/double</li>
 *   <li>Scale of 4 for internal storage (sub-paise precision for accrual)</li>
 *   <li>RoundingMode.HALF_EVEN (banker's rounding) as default</li>
 *   <li>Immutable — all operations return new instances</li>
 *   <li>Currency-safe — operations between different currencies throw</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    public static final int DEFAULT_SCALE = 4;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;
    public static final MathContext DEFAULT_MATH_CONTEXT = MathContext.DECIMAL128;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = Objects.requireNonNull(amount, "Amount must not be null")
                .setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Factory Methods
    // ═══════════════════════════════════════════════════════════════════

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(long amountInMinorUnits, Currency currency) {
        BigDecimal amount = BigDecimal.valueOf(amountInMinorUnits)
                .movePointLeft(currency.getDefaultFractionDigits());
        return new Money(amount, currency);
    }

    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Arithmetic Operations (all return new Money instances)
    // ═══════════════════════════════════════════════════════════════════

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(this.amount.multiply(factor, DEFAULT_MATH_CONTEXT), this.currency);
    }

    public Money multiply(long factor) {
        return multiply(BigDecimal.valueOf(factor));
    }

    public Money divide(BigDecimal divisor) {
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Cannot divide money by zero");
        }
        return new Money(this.amount.divide(divisor, DEFAULT_SCALE, DEFAULT_ROUNDING), this.currency);
    }

    public Money divide(int divisor) {
        return divide(BigDecimal.valueOf(divisor));
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }

    public Money abs() {
        return new Money(this.amount.abs(), this.currency);
    }

    public Money min(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) <= 0 ? this : other;
    }

    public Money max(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0 ? this : other;
    }

    /**
     * Round to a specific scale (e.g., 2 for display, 0 for whole currency units).
     */
    public Money roundTo(int scale, RoundingMode roundingMode) {
        return new Money(this.amount.setScale(scale, roundingMode), this.currency);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Comparison
    // ═══════════════════════════════════════════════════════════════════

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0;
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) <= 0;
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getCurrencyCode() {
        return currency.getCurrencyCode();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Equality & Hashing (value-based)
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amount.compareTo(money.amount) == 0
                && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════════

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(
                    "Cannot operate on different currencies: "
                            + this.currency.getCurrencyCode() + " vs "
                            + other.currency.getCurrencyCode());
        }
    }
}
