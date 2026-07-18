package com.originex.los.application.service;

import com.originex.common.money.Money;
import com.originex.los.application.port.out.CustomerVerificationPort.BeneficiaryAccount;
import com.originex.los.domain.model.LoanApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer-side contract guard for {@code originex.los.DisbursementRequested} — the entry-point
 * hop of the loan lifecycle. This is the cheap alternative (chosen over a full multi-service
 * compose e2e) to the "green tests, broken reality" risk: a producer silently dropping or
 * renaming a field that a downstream consumer parses, with every isolated test still green.
 *
 * <p>The consumer this must satisfy is lms's {@code DisbursementRequestedConsumer}, which reads
 * these fields <b>unconditionally</b> (a missing one is an NPE on {@code json.get(...).asText()}):
 * {@code customer_id}, {@code application_id}, {@code product_code}, {@code sanctioned_amount},
 * {@code interest_rate}, {@code tenure_months}, {@code emi}. The {@code beneficiary_*} fields are
 * optional to lms but must be carried through, because lms copies them onto its {@code LoanDisbursed}
 * event and payment's consumer <i>requires</i> {@code beneficiary_account}/{@code beneficiary_ifsc}.
 *
 * <p>Asserts field <b>keys</b>, not values — the contract is "the field is present", so the guard
 * fails on a drop/rename (the actual drift risk) without being brittle to value changes.
 */
@DisplayName("los DisbursementRequested payload — carries the fields lms and payment require")
class DisbursementRequestedPayloadContractTest {

    @Test
    @DisplayName("the emitted payload contains every field its downstream consumers parse")
    void payloadCarriesConsumerRequiredFields() {
        LoanApplication app = LoanApplication.submit(
                UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN",
                Money.of("300000", "INR"), 18,
                "Home improvement", "MOBILE_APP",
                "Priya Sharma", "ABCDE1234F",
                "SALARIED", Money.of("65000", "INR"));
        app.startProcessing();
        app.recordCreditCheck(720, "CIBIL", "CR-2026-07-001");
        app.approve("Good profile");
        app.generateOffer(
                Money.of("280000", "INR"), new BigDecimal("11.5"), 18,
                Money.of("17122", "INR"), Money.of("2800", "INR"), new BigDecimal("12.8"),
                Instant.now().plus(7, ChronoUnit.DAYS));

        BeneficiaryAccount beneficiary =
                new BeneficiaryAccount("1234567890", "SBIN0001234", "Priya Sharma", "SBI");

        String payload = new String(
                LoanApplicationService.buildDisbursementRequestedPayload(app, beneficiary),
                StandardCharsets.UTF_8);

        assertThat(payload)
                .as("lms DisbursementRequestedConsumer reads these unconditionally — a missing one NPEs")
                .contains("\"customer_id\":")
                .contains("\"application_id\":")
                .contains("\"product_code\":")
                .contains("\"sanctioned_amount\":")
                .contains("\"interest_rate\":")
                .contains("\"tenure_months\":")
                .contains("\"emi\":");
        assertThat(payload)
                .as("carried through lms LoanDisbursed to payment, which requires the beneficiary fields")
                .contains("\"beneficiary_account\":")
                .contains("\"beneficiary_ifsc\":");
    }
}
