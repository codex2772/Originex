package com.originex.lms.domain.model;

import com.originex.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

        @Test
        void initiateDisbursementMovesToPendingAndCreatesInitiatedDisbursement() {
            // Contract that createLoan (service) now relies on: after initiate, the loan
            // is PENDING_DISBURSAL with one INITIATED disbursement for confirmDisbursementByPayment.
            Loan loan = Loan.createFromApplication(
                    TENANT, CUSTOMER, APPLICATION, "PERSONAL_LOAN",
                    Money.of("500000", "INR"),
                    new BigDecimal("12.5"), "FIXED",
                    24, Money.of("23536.74", "INR"));

            loan.initiateDisbursement(Money.of("500000", "INR"), "ACC-999");

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.PENDING_DISBURSAL);
            assertThat(loan.getDisbursements()).hasSize(1);
            assertThat(loan.getDisbursements().get(0).getStatus())
                    .isEqualTo(Disbursement.DisbursementStatus.INITIATED);
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
    @DisplayName("Schedule Settlement On Repayment")
    class ScheduleSettlement {

        private static final LocalDate D1 = LocalDate.now().plusDays(30);

        // 3 installments of 100000 principal + 5000 interest (total 105000 each).
        private Loan activeLoanWithSchedule() {
            Loan loan = createActiveLoan();
            List<Installment> schedule = List.of(
                    Installment.create(1, D1, Money.of("100000", "INR"), Money.of("5000", "INR")),
                    Installment.create(2, D1.plusMonths(1), Money.of("100000", "INR"), Money.of("5000", "INR")),
                    Installment.create(3, D1.plusMonths(2), Money.of("100000", "INR"), Money.of("5000", "INR"))
            );
            loan.setSchedule(schedule);
            return loan;
        }

        @Test
        void shouldMarkOldestInstallmentPaidAndAdvanceNextDueDate() {
            Loan loan = activeLoanWithSchedule();
            loan.accrueInterest(Money.of("5000", "INR"));
            assertThat(loan.getNextDueDate()).isEqualTo(D1);

            loan.allocateRepayment(Money.of("105000", "INR")); // 5000 interest + 100000 principal

            Installment first = loan.getInstallments().get(0);
            assertThat(first.getStatus()).isEqualTo(Installment.InstallmentStatus.PAID);
            assertThat(first.getInterestPaid().getAmount()).isEqualByComparingTo("5000.0000");
            assertThat(first.getPrincipalPaid().getAmount()).isEqualByComparingTo("100000.0000");
            assertThat(loan.getInstallments().get(1).getStatus()).isEqualTo(Installment.InstallmentStatus.UPCOMING);
            assertThat(loan.getNextDueDate()).isEqualTo(D1.plusMonths(1));
        }

        @Test
        void shouldPartiallyPayAndKeepNextDueDateOnSameInstallment() {
            Loan loan = activeLoanWithSchedule();
            // No accrued interest → whole 50000 is principal at loan level; the
            // installment still fills its interest-due first, then principal.
            loan.allocateRepayment(Money.of("50000", "INR"));

            Installment first = loan.getInstallments().get(0);
            assertThat(first.getStatus()).isEqualTo(Installment.InstallmentStatus.PARTIALLY_PAID);
            assertThat(first.getInterestPaid().getAmount()).isEqualByComparingTo("5000.0000");
            assertThat(first.getPrincipalPaid().getAmount()).isEqualByComparingTo("45000.0000");
            assertThat(loan.getNextDueDate()).isEqualTo(D1); // oldest unpaid unchanged
        }

        @Test
        void shouldSettleMultipleInstallmentsOldestFirst() {
            Loan loan = activeLoanWithSchedule();
            loan.allocateRepayment(Money.of("210000", "INR")); // covers installments 1 and 2

            assertThat(loan.getInstallments().get(0).getStatus()).isEqualTo(Installment.InstallmentStatus.PAID);
            assertThat(loan.getInstallments().get(1).getStatus()).isEqualTo(Installment.InstallmentStatus.PAID);
            assertThat(loan.getInstallments().get(2).getStatus()).isEqualTo(Installment.InstallmentStatus.UPCOMING);
            assertThat(loan.getNextDueDate()).isEqualTo(D1.plusMonths(2));
        }

        @Test
        void shouldMarkAllInstallmentsPaidWhenLoanMatures() {
            Loan loan = activeLoanWithSchedule();
            loan.allocateRepayment(Money.of("500000", "INR")); // zeroes loan principal → MATURED

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.MATURED);
            assertThat(loan.getInstallments())
                    .allMatch(i -> i.getStatus() == Installment.InstallmentStatus.PAID);
        }

        @Test
        void shouldNotTouchScheduleWhenLoadedWithoutInstallments() {
            // A loan loaded without its children (e.g. a lean path) must not be
            // corrupted by a repayment — settlement is a no-op, balances still move.
            Loan loan = createActiveLoan(); // no schedule set
            loan.allocateRepayment(Money.of("10000", "INR"));
            assertThat(loan.getInstallments()).isEmpty();
            assertThat(loan.getOutstandingPrincipal().getAmount()).isEqualByComparingTo("490000.0000");
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

        @Test
        void calculateDpdIsDaysSinceOldestUnpaidDueDate() {
            Loan loan = createActiveLoan();
            loan.setSchedule(List.of(
                    Installment.create(1, LocalDate.now().minusDays(45), Money.of("100000", "INR"), Money.of("5000", "INR"))));

            assertThat(loan.calculateDpd(LocalDate.now())).isEqualTo(45);
        }

        @Test
        void calculateDpdIsZeroWhenNotYetDueOrNoSchedule() {
            Loan noSchedule = createActiveLoan();
            assertThat(noSchedule.calculateDpd(LocalDate.now())).isZero();

            Loan current = createActiveLoan();
            current.setSchedule(List.of(
                    Installment.create(1, LocalDate.now().plusDays(10), Money.of("100000", "INR"), Money.of("5000", "INR"))));
            assertThat(current.calculateDpd(LocalDate.now())).isZero();
        }

        @Test
        void agingClassifiesNpaOnceOverdue90Days() {
            // Drives the exact logic the DPD aging job runs: calculateDpd → updateDpd.
            Loan loan = createActiveLoan();
            loan.setSchedule(List.of(
                    Installment.create(1, LocalDate.now().minusDays(95), Money.of("100000", "INR"), Money.of("5000", "INR"))));

            loan.updateDpd(loan.calculateDpd(LocalDate.now()));

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.NPA);
            assertThat(loan.getAssetClassification()).isEqualTo("SUB_STANDARD");
            assertThat(loan.getMaxDpd()).isEqualTo(95);
        }
    }
}
