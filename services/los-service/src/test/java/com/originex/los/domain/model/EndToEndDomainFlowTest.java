package com.originex.los.domain.model;

import com.originex.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end domain flow test: Application submit → Credit Check → Approve → Offer → Accept → Disburse
 * Tests the full business lifecycle without infrastructure (pure domain logic).
 */
@DisplayName("LOS → LMS End-to-End Domain Flow")
class EndToEndDomainFlowTest {

    @Test
    @DisplayName("Full happy path: submit → credit → approve → offer → accept → disbursement requested")
    void fullHappyPath() {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // ─── Step 1: Customer submits loan application ───
        LoanApplication app = LoanApplication.submit(
                tenantId, customerId, "PERSONAL_LOAN",
                Money.of("300000", "INR"), 18,
                "Home improvement", "MOBILE_APP",
                "Priya Sharma", "ABCDE1234F",
                "SALARIED", Money.of("65000", "INR")
        );
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(app.getApplicationId()).isNotNull();

        // ─── Step 2: System starts processing (BRE evaluation begins) ───
        app.startProcessing();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.IN_PROGRESS);

        // ─── Step 3: Credit bureau check returns score ───
        app.recordCreditCheck(720, "CIBIL", "CR-2026-07-001");
        assertThat(app.getCreditScore()).isEqualTo(720);

        // ─── Step 4: Underwriter approves ───
        app.approve("Good credit history, stable income, low FOIR");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getDecisionNotes()).contains("Good credit history");

        // ─── Step 5: Offer generated (amounts, EMI calculated) ───
        Money sanctioned = Money.of("280000", "INR");
        BigDecimal rate = new BigDecimal("11.5");
        Money emi = Money.of("17122", "INR");
        Money processingFee = Money.of("2800", "INR");
        BigDecimal apr = new BigDecimal("12.8");
        Instant offerExpiry = Instant.now().plus(7, ChronoUnit.DAYS);

        app.generateOffer(sanctioned, rate, 18, emi, processingFee, apr, offerExpiry);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFER_PENDING);
        assertThat(app.getCurrentOffer()).isNotNull();
        assertThat(app.getCurrentOffer().getSanctionedAmount().getAmount())
                .isEqualByComparingTo("280000.0000");
        assertThat(app.getCurrentOffer().getEmi().getAmount())
                .isEqualByComparingTo("17122.0000");

        // ─── Step 6: Customer accepts offer ───
        app.acceptOffer();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFER_ACCEPTED);

        // ─── Step 7: System requests disbursement ───
        app.requestDisbursement();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSEMENT_REQUESTED);
        assertThat(app.getStatus().isTerminal()).isTrue();

        // ─── At this point, DisbursementRequested event would be published ───
        // ─── LMS consumes it and creates the Loan aggregate ───

        // Verify final state
        assertThat(app.getApplicationId()).isNotNull();
        assertThat(app.getCustomerId()).isEqualTo(customerId);
        assertThat(app.getCurrentOffer().getInterestRate()).isEqualByComparingTo("11.5");
    }

    @Test
    @DisplayName("Rejection path: submit → credit → reject")
    void rejectionPath() {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        LoanApplication app = LoanApplication.submit(
                tenantId, customerId, "PERSONAL_LOAN",
                Money.of("1000000", "INR"), 36,
                "Business expansion", "WEB",
                "Anil Kumar", "XYZAB9876P",
                "SELF_EMPLOYED", Money.of("45000", "INR")
        );

        app.startProcessing();
        app.recordCreditCheck(580, "EXPERIAN", "EXP-2026-002");

        // Low score + high loan amount → reject
        app.reject("Credit score below threshold (580 < 650). High loan-to-income ratio.");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getStatus().isTerminal()).isTrue();
        assertThat(app.getDecisionNotes()).contains("below threshold");
    }

    @Test
    @DisplayName("Withdrawal path: customer withdraws mid-process")
    void withdrawalPath() {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        LoanApplication app = LoanApplication.submit(
                tenantId, customerId, "HOME_LOAN",
                Money.of("5000000", "INR"), 240,
                "New house", "BRANCH",
                "Sunita Patel", "PQRST5678K",
                "SALARIED", Money.of("120000", "INR")
        );

        app.startProcessing();
        app.recordCreditCheck(750, "CIBIL", "CR-2026-003");
        app.approve("Excellent profile");

        // Customer changes mind before offer
        app.withdraw();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
        assertThat(app.getStatus().isTerminal()).isTrue();
    }
}
