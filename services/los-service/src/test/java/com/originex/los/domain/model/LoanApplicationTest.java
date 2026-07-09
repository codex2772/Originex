package com.originex.los.domain.model;

import com.originex.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoanApplication Aggregate — State Machine")
class LoanApplicationTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    private LoanApplication createSubmittedApplication() {
        return LoanApplication.submit(
                TENANT_ID, CUSTOMER_ID, "PERSONAL_LOAN",
                Money.of("500000", "INR"), 24,
                "Home renovation", "MOBILE_APP",
                "John Doe", "ABCDE1234F", "SALARIED",
                Money.of("75000", "INR")
        );
    }

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        void shouldCreateInSubmittedState() {
            LoanApplication app = createSubmittedApplication();

            assertThat(app.getApplicationId()).isNotNull();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
            assertThat(app.getRequestedAmount().getAmount()).isEqualByComparingTo("500000.0000");
            assertThat(app.getRequestedTenureMonths()).isEqualTo(24);
            assertThat(app.getCustomerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        void shouldRejectNegativeAmount() {
            assertThatThrownBy(() -> LoanApplication.submit(
                    TENANT_ID, CUSTOMER_ID, "PL",
                    Money.of("-100", "INR"), 12,
                    null, null, null, null, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void shouldRejectInvalidTenure() {
            assertThatThrownBy(() -> LoanApplication.submit(
                    TENANT_ID, CUSTOMER_ID, "PL",
                    Money.of("100000", "INR"), 0,
                    null, null, null, null, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1-360");
        }
    }

    @Nested
    @DisplayName("State Transitions — Happy Path")
    class HappyPath {

        @Test
        void shouldTransitionThroughFullLifecycle() {
            LoanApplication app = createSubmittedApplication();

            // SUBMITTED → IN_PROGRESS
            app.startProcessing();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.IN_PROGRESS);

            // Record credit check
            app.recordCreditCheck(750, "CIBIL", "REF-123");
            assertThat(app.getCreditScore()).isEqualTo(750);

            // IN_PROGRESS → APPROVED
            app.approve("Good credit profile");
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

            // APPROVED → OFFER_PENDING
            app.generateOffer(
                    Money.of("450000", "INR"),
                    new BigDecimal("12.5"),
                    24,
                    Money.of("21250", "INR"),
                    Money.of("4500", "INR"),
                    new BigDecimal("13.8"),
                    Instant.now().plus(7, ChronoUnit.DAYS)
            );
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFER_PENDING);
            assertThat(app.getCurrentOffer()).isNotNull();
            assertThat(app.getCurrentOffer().getSanctionedAmount().getAmount())
                    .isEqualByComparingTo("450000.0000");

            // OFFER_PENDING → OFFER_ACCEPTED → DISBURSEMENT_REQUESTED
            app.acceptOffer();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFER_ACCEPTED);

            app.requestDisbursement();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_REQUESTED);
            assertThat(app.getStatus().isTerminal()).isTrue();
        }
    }

    @Nested
    @DisplayName("State Transitions — Invalid")
    class InvalidTransitions {

        @Test
        void shouldRejectDirectApprovalFromSubmitted() {
            LoanApplication app = createSubmittedApplication();
            assertThatThrownBy(() -> app.approve("skip processing"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid transition");
        }

        @Test
        void shouldRejectOfferOnUnapprovedApplication() {
            LoanApplication app = createSubmittedApplication();
            app.startProcessing();
            assertThatThrownBy(() -> app.generateOffer(
                    Money.of("450000", "INR"), new BigDecimal("12.5"), 24,
                    Money.of("21250", "INR"), Money.of("0", "INR"),
                    new BigDecimal("12.5"), Instant.now().plus(7, ChronoUnit.DAYS)
            )).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldNotWithdrawTerminalApplication() {
            LoanApplication app = createSubmittedApplication();
            app.startProcessing();
            app.reject("Low income");
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);

            assertThatThrownBy(app::withdraw)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }

        @Test
        void shouldRejectExpiredOffer() {
            LoanApplication app = createSubmittedApplication();
            app.startProcessing();
            app.recordCreditCheck(700, "CIBIL", "REF");
            app.approve("ok");
            app.generateOffer(
                    Money.of("400000", "INR"), new BigDecimal("14"), 24,
                    Money.of("19500", "INR"), Money.of("0", "INR"),
                    new BigDecimal("14"), Instant.now().minus(1, ChronoUnit.DAYS) // Already expired
            );

            assertThatThrownBy(app::acceptOffer)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expired");
        }
    }

    @Nested
    @DisplayName("Offer Constraints")
    class OfferConstraints {

        @Test
        void shouldRejectOfferExceedingRequestedAmount() {
            LoanApplication app = createSubmittedApplication();
            app.startProcessing();
            app.recordCreditCheck(800, "CIBIL", "REF");
            app.approve("excellent");

            assertThatThrownBy(() -> app.generateOffer(
                    Money.of("600000", "INR"), // Exceeds 500000 requested
                    new BigDecimal("12"), 24,
                    Money.of("28000", "INR"), Money.of("0", "INR"),
                    new BigDecimal("12"), Instant.now().plus(7, ChronoUnit.DAYS)
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceed");
        }
    }

    @Nested
    @DisplayName("Withdrawal")
    class Withdrawal {

        @Test
        void shouldAllowWithdrawalFromAnyNonTerminalState() {
            LoanApplication app = createSubmittedApplication();
            app.withdraw();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
            assertThat(app.getStatus().isTerminal()).isTrue();
        }
    }
}
