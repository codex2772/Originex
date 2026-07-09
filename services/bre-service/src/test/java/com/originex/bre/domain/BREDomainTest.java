package com.originex.bre.domain;

import com.originex.bre.domain.engine.OfferCalculator;
import com.originex.bre.domain.engine.RuleEvaluationEngine;
import com.originex.bre.domain.model.*;
import com.originex.bre.domain.model.EvaluationResult.Decision;
import com.originex.bre.domain.model.EvaluationResult.RuleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BRE Domain — Rule Evaluation Engine & Offer Calculator")
class BREDomainTest {

    private RuleEvaluationEngine engine;
    private OfferCalculator offerCalculator;
    private static final UUID TENANT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new RuleEvaluationEngine();
        offerCalculator = new OfferCalculator();
    }

    // ─── Helper to build standard rule set ───

    private List<Rule> buildStandardRules() {
        return List.of(
                rule("MIN_CREDIT_SCORE", Rule.RuleType.HARD, Rule.RuleCategory.CREDIT,
                        "credit_score", Rule.RuleOperator.GTE, "600", null, 10),
                rule("NO_WRITE_OFF", Rule.RuleType.HARD, Rule.RuleCategory.DELINQUENCY,
                        "has_write_off", Rule.RuleOperator.EQ, "false", null, 20),
                rule("MIN_AGE", Rule.RuleType.HARD, Rule.RuleCategory.AGE,
                        "applicant_age", Rule.RuleOperator.GTE, "21", null, 30),
                rule("MAX_AGE_AT_MATURITY", Rule.RuleType.HARD, Rule.RuleCategory.AGE,
                        "age_at_maturity", Rule.RuleOperator.LTE, "65", null, 40),
                rule("MIN_INCOME", Rule.RuleType.HARD, Rule.RuleCategory.INCOME,
                        "monthly_income", Rule.RuleOperator.GTE, "15000", null, 50),
                rule("MAX_FOIR", Rule.RuleType.SOFT, Rule.RuleCategory.INCOME,
                        "foir", Rule.RuleOperator.LTE, "0.50", null, 60),
                rule("MAX_ENQUIRIES", Rule.RuleType.SOFT, Rule.RuleCategory.CREDIT,
                        "enquiries_last_6_months", Rule.RuleOperator.LTE, "4", null, 70)
        );
    }

    private EvaluationFacts goodApplicant() {
        return EvaluationFacts.of(
                TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PERSONAL_LOAN", 750, "CIBIL", false, false, 2, 1,
                new BigDecimal("5000"), "SALARIED", new BigDecimal("60000"),
                30, 36, new BigDecimal("300000"),
                offerCalculator.calculateEmi(new BigDecimal("300000"), new BigDecimal("12.00"), 36),
                "INR"
        );
    }

    @Nested
    @DisplayName("Rule Evaluation — Happy Path (All Pass)")
    class HappyPath {
        @Test
        void allRulesPassForGoodApplicant() {
            List<RuleResult> results = engine.evaluate(buildStandardRules(), goodApplicant());

            assertThat(results).allMatch(RuleResult::passed);
            assertThat(engine.deriveDecision(results)).isEqualTo(Decision.APPROVED);
        }

        @Test
        void riskGradeIsLowForHighCreditScore() {
            String grade = engine.computeRiskGrade(goodApplicant());
            assertThat(grade).isEqualTo("LOW");
        }
    }

    @Nested
    @DisplayName("HARD Rule Failures → REJECTED")
    class HardRuleFailures {
        @Test
        void creditScoreBelowMinimumCausesRejection() {
            EvaluationFacts facts = EvaluationFacts.of(
                    TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "PERSONAL_LOAN", 550, "CIBIL", false, false, 1, 0,
                    BigDecimal.ZERO, "SALARIED", new BigDecimal("50000"),
                    28, 36, new BigDecimal("200000"),
                    offerCalculator.calculateEmi(new BigDecimal("200000"), new BigDecimal("14.00"), 36), "INR"
            );

            List<RuleResult> results = engine.evaluate(buildStandardRules(), facts);
            assertThat(engine.deriveDecision(results)).isEqualTo(Decision.REJECTED);

            RuleResult creditRule = results.stream()
                    .filter(r -> r.ruleCode().equals("MIN_CREDIT_SCORE"))
                    .findFirst().orElseThrow();
            assertThat(creditRule.passed()).isFalse();
            assertThat(creditRule.failureMessage()).isNotBlank();
        }

        @Test
        void writeOffCausesRejection() {
            EvaluationFacts facts = EvaluationFacts.of(
                    TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "PERSONAL_LOAN", 720, "CIBIL", true, false, 1, 0,
                    BigDecimal.ZERO, "SALARIED", new BigDecimal("50000"),
                    28, 36, new BigDecimal("200000"),
                    offerCalculator.calculateEmi(new BigDecimal("200000"), new BigDecimal("12.00"), 36), "INR"
            );

            List<RuleResult> results = engine.evaluate(buildStandardRules(), facts);
            assertThat(engine.deriveDecision(results)).isEqualTo(Decision.REJECTED);
        }

        @Test
        void ageAtMaturityExceedingLimitCausesRejection() {
            // 60 years old + 7 year loan = maturity at 67 (> 65)
            EvaluationFacts facts = EvaluationFacts.of(
                    TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "PERSONAL_LOAN", 750, "CIBIL", false, false, 1, 0,
                    BigDecimal.ZERO, "SALARIED", new BigDecimal("60000"),
                    60, 84, new BigDecimal("200000"),
                    offerCalculator.calculateEmi(new BigDecimal("200000"), new BigDecimal("11.25"), 84), "INR"
            );

            List<RuleResult> results = engine.evaluate(buildStandardRules(), facts);
            assertThat(engine.deriveDecision(results)).isEqualTo(Decision.REJECTED);

            RuleResult ageRule = results.stream()
                    .filter(r -> r.ruleCode().equals("MAX_AGE_AT_MATURITY"))
                    .findFirst().orElseThrow();
            assertThat(ageRule.passed()).isFalse();
        }
    }

    @Nested
    @DisplayName("SOFT Rule Failures → REFER_TO_UNDERWRITER")
    class SoftRuleFailures {
        @Test
        void highFoirRefersToUnderwriter() {
            // FOIR > 50%: 35000 existing EMI + proposed EMI on 60k income
            EvaluationFacts facts = EvaluationFacts.of(
                    TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "PERSONAL_LOAN", 720, "CIBIL", false, false, 2, 1,
                    new BigDecimal("35000"), // very high existing obligations
                    "SALARIED", new BigDecimal("60000"),
                    30, 36, new BigDecimal("300000"),
                    offerCalculator.calculateEmi(new BigDecimal("300000"), new BigDecimal("12.50"), 36), "INR"
            );

            List<RuleResult> results = engine.evaluate(buildStandardRules(), facts);
            assertThat(engine.deriveDecision(results)).isEqualTo(Decision.REFER_TO_UNDERWRITER);
        }

        @Test
        void tooManyEnquiriesRefersToUnderwriter() {
            EvaluationFacts facts = EvaluationFacts.of(
                    TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "PERSONAL_LOAN", 720, "CIBIL", false, false, 6, 2,
                    new BigDecimal("5000"), "SALARIED", new BigDecimal("60000"),
                    30, 36, new BigDecimal("300000"),
                    offerCalculator.calculateEmi(new BigDecimal("300000"), new BigDecimal("12.50"), 36), "INR"
            );

            List<RuleResult> results = engine.evaluate(buildStandardRules(), facts);
            assertThat(engine.deriveDecision(results)).isEqualTo(Decision.REFER_TO_UNDERWRITER);
        }
    }

    @Nested
    @DisplayName("Offer Calculator")
    class OfferCalc {
        @Test
        void emiCalculationIsCorrect() {
            // Standard 3-lakh personal loan at 12% for 36 months
            // Expected EMI ≈ ₹9,963
            BigDecimal emi = offerCalculator.calculateEmi(
                    new BigDecimal("300000"), new BigDecimal("12.00"), 36);

            assertThat(emi).isBetween(new BigDecimal("9900"), new BigDecimal("10050"));
        }

        @Test
        void rateIsLowerForHighCreditScore() {
            BigDecimal rateHigh = offerCalculator.determineRate(810, "LOW", "PERSONAL_LOAN");
            BigDecimal rateLow  = offerCalculator.determineRate(650, "HIGH", "PERSONAL_LOAN");

            assertThat(rateHigh).isLessThan(rateLow);
        }

        @Test
        void approvedAmountReducedWhenFoirTooHigh() {
            // Applicant with ₹30k monthly income and ₹10k existing EMI
            EvaluationFacts facts = EvaluationFacts.of(
                    TENANT, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "PERSONAL_LOAN", 720, "CIBIL", false, false, 1, 1,
                    new BigDecimal("10000"), "SALARIED", new BigDecimal("30000"),
                    30, 36, new BigDecimal("500000"),
                    offerCalculator.calculateEmi(new BigDecimal("500000"), new BigDecimal("12.50"), 36), "INR"
            );

            BigDecimal approved = offerCalculator.determineApprovedAmount(facts, new BigDecimal("12.50"));

            // Max EMI capacity = 30000 * 0.50 - 10000 = 5000
            // approved amount should be less than requested 500000
            assertThat(approved).isLessThan(new BigDecimal("500000"));
        }

        @Test
        void zeroAmountForEmiCalculationReturnsZero() {
            BigDecimal emi = offerCalculator.calculateEmi(BigDecimal.ZERO, new BigDecimal("12.00"), 12);
            assertThat(emi).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ─── Helper ───

    private Rule rule(String code, Rule.RuleType type, Rule.RuleCategory category,
                       String factKey, Rule.RuleOperator op,
                       String threshold, String thresholdMax, int priority) {
        Rule r = new Rule();
        r.setRuleId(UUID.randomUUID());
        r.setTenantId(TENANT);
        r.setRuleSetId(UUID.randomUUID());
        r.setRuleCode(code);
        r.setRuleType(type);
        r.setCategory(category);
        r.setFactKey(factKey);
        r.setOperator(op);
        r.setThresholdValue(threshold);
        r.setThresholdValueMax(thresholdMax);
        r.setFailureMessage(code + " violated — value does not meet threshold " + threshold);
        r.setPriority(priority);
        r.setActive(true);
        return r;
    }
}
