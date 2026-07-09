package com.originex.lms.domain.model;

import com.originex.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Loan Aggregate — Repayment Waterfall & Lifecycle")
class LoanTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();

    private Loan createActiveLoan() {
        Loan loan = Loan.createFromApplication(
                TENANT, CUSTOMER, APPLICATION, "PERSONAL_LOAN",
                Money.of("500000", "INR"),
                new BigDecimal("12.5"), "FIXED",
                24, Money.of("23536.74", "INR")
        );
        loan.initiateDisbursement(Money.of("500000", "INR"), "ACC-123456");
        UUID disbId = loan.getDisbursements().get(0).getDisbursementId();
        loan.confirmDisbursement(disbId, "PAY-REF-001");
        return loan;
    }

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        void shouldCreateLoanWithZeroBalances() {
            Loan loan = Loan.createFromApplication(
                    TENANT, CUSTOMER, APPLICATION, "PL",
                    Money.of("100000", "INR"),
                    new BigDecimal("10"), "FIXED",
                    12, Money.of("8792", "INR")
            );

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.CREATED);
            assertThat(loan.getDisbursedAmount().isZero()).isTrue();
            assertThat(loan.getOutstandingPrincipal().isZero()).isTrue();
            assertThat(loan.getLoanAccountNumber()).startsWith("OX");
        }
    }

    @Nested
    @DisplayName("Disbursement")
    class DisbursementTests {

        @Test
        void shouldNotExceedSanctionedAmount() {
            Loan loan = Loan.createFromApplication(
                    TENANT, CUSTOMER, APPLICATION, "PL",
                    Money.of("100000", "INR"),
                    new BigDecimal("10"), "FIXED",
                    12, Money.of("8792", "INR")
            );

            assertThatThrownBy(() ->
                    loan.initiateDisbursement(Money.of("200000", "INR"), "ACC-123")
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceed");
        }

        @Test
        void shouldUpdateBalanceAfterDisbursement() {
            Loan loan = createActiveLoan();

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
            assertThat(loan.getDisbursedAmount().getAmount()).isEqualByComparingTo("500000.0000");
            assertThat(loan.getOutstandingPrincipal().getAmount()).isEqualByComparingTo("500000.0000");
        }
    }

    @Nested
    @DisplayName("Interest Accrual")
    class InterestAccrual {

        @Test
        void shouldSeedLastAccrualDateOnActivation() {
            Loan loan = createActiveLoan();
            // confirmDisbursement sets lastAccrualDate = firstDisbursementDate (today)
            assertThat(loan.getLastAccrualDate()).isEqualTo(java.time.LocalDate.now());
        }

        @Test
        void shouldIncreaseOutstandingInterestWhenAccruing() {
            Loan loan = createActiveLoan();
            loan.accrueInterest(Money.of("1234.5678", "INR"));
            assertThat(loan.getOutstandingInterest().getAmount()).isEqualByComparingTo("1234.5678");
        }

        @Test
        void shouldRejectAccrualOnNonActiveLoan() {
            // Fresh loan is CREATED (not ACTIVE/NPA)
            Loan loan = Loan.createFromApplication(
                    TENANT, CUSTOMER, APPLICATION, "PL",
                    Money.of("100000", "INR"),
                    new BigDecimal("10"), "FIXED",
                    12, Money.of("8792", "INR"));

            assertThatThrownBy(() -> loan.accrueInterest(Money.of("100", "INR")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not active");
        }
    }

    @Nested
    @DisplayName("Repayment Waterfall: Charges → Interest → Principal")
    class RepaymentWaterfall {

        @Test
        void shouldAllocateToInterestFirstThenPrincipal() {
            Loan loan = createActiveLoan();
            // Accrue some interest
            loan.accrueInterest(Money.of("5000", "INR"));

            assertThat(loan.getOutstandingInterest().getAmount()).isEqualByComparingTo("5000.0000");

            // Payment of 25000: should pay 5000 interest + 20000 principal
            Loan.RepaymentAllocation alloc = loan.allocateRepayment(Money.of("25000", "INR"));

            assertThat(alloc.interestAllocated().getAmount()).isEqualByComparingTo("5000.0000");
            assertThat(alloc.principalAllocated().getAmount()).isEqualByComparingTo("20000.0000");
            assertThat(alloc.chargesAllocated().isZero()).isTrue();
            assertThat(alloc.excess().isZero()).isTrue();

            // Verify outstanding balances updated
            assertThat(loan.getOutstandingInterest().isZero()).isTrue();
            assertThat(loan.getOutstandingPrincipal().getAmount()).isEqualByComparingTo("480000.0000");
        }

        @Test
        void shouldAllocateChargesFirst() {
            Loan loan = createActiveLoan();
            loan.accrueInterest(Money.of("5000", "INR"));
            loan.levyCharge(Money.of("500", "INR"), "LATE_FEE");

            // Payment of 6000: 500 charges + 5000 interest + 500 principal
            Loan.RepaymentAllocation alloc = loan.allocateRepayment(Money.of("6000", "INR"));

            assertThat(alloc.chargesAllocated().getAmount()).isEqualByComparingTo("500.0000");
            assertThat(alloc.interestAllocated().getAmount()).isEqualByComparingTo("5000.0000");
            assertThat(alloc.principalAllocated().getAmount()).isEqualByComparingTo("500.0000");
            assertThat(alloc.excess().isZero()).isTrue();
        }

        @Test
        void shouldReturnExcessWhenOverpaying() {
            Loan loan = createActiveLoan();
            // Pay more than total outstanding (principal only, no interest)
            Loan.RepaymentAllocation alloc = loan.allocateRepayment(Money.of("600000", "INR"));

            assertThat(alloc.principalAllocated().getAmount()).isEqualByComparingTo("500000.0000");
            assertThat(alloc.excess().getAmount()).isEqualByComparingTo("100000.0000");
            assertThat(loan.getStatus()).isEqualTo(LoanStatus.MATURED);
        }

        @Test
        void shouldMatureWhenFullyPaid() {
            Loan loan = createActiveLoan();
            loan.allocateRepayment(Money.of("500000", "INR"));
            assertThat(loan.getStatus()).isEqualTo(LoanStatus.MATURED);
        }
    }

    @Nested
    @DisplayName("NPA Classification")
    class NPAClassification {

        @Test
        void shouldClassifyNPAAt90DPD() {
            Loan loan = createActiveLoan();
            loan.updateDpd(89);
            assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);

            loan.updateDpd(90);
            assertThat(loan.getStatus()).isEqualTo(LoanStatus.NPA);
            assertThat(loan.getAssetClassification()).isEqualTo("SUB_STANDARD");
        }

        @Test
        void shouldEscalateClassificationByDPD() {
            Loan loan = createActiveLoan();
            loan.updateDpd(365);
            assertThat(loan.getAssetClassification()).isEqualTo("DOUBTFUL");

            loan.updateDpd(730);
            assertThat(loan.getAssetClassification()).isEqualTo("LOSS");
        }
    }
}
