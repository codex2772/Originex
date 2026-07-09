package com.originex.bre.domain.engine;

import com.originex.bre.domain.model.*;
import com.originex.bre.domain.model.EvaluationResult.RuleResult;
import com.originex.bre.domain.model.Rule.RuleOperator;
import com.originex.bre.domain.model.Rule.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * RuleEvaluationEngine — the heart of the BRE.
 *
 * <p>Evaluates a set of {@link Rule}s against {@link EvaluationFacts} and
 * produces an {@link EvaluationResult}.
 *
 * <p>Evaluation strategy:
 * <ol>
 *   <li>Rules are sorted by priority (ascending)</li>
 *   <li>HARD rules are evaluated first — first failure immediately rejects</li>
 *   <li>SOFT rule failures accumulate — if any SOFT rule fails, result is REFER</li>
 *   <li>ADVISORY rule failures are noted but do not affect the decision</li>
 *   <li>If all rules pass → APPROVED with calculated offer</li>
 * </ol>
 */
@Service
public class RuleEvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationEngine.class);

    /**
     * Evaluate all rules in the rule set against the provided facts.
     */
    public List<RuleResult> evaluate(List<Rule> rules, EvaluationFacts facts) {
        List<RuleResult> results = new ArrayList<>();

        List<Rule> sorted = rules.stream()
                .filter(Rule::isActive)
                .sorted(Comparator.comparingInt(Rule::getPriority))
                .toList();

        for (Rule rule : sorted) {
            String factValue = extractFact(rule.getFactKey(), facts);
            boolean passed = evaluateCondition(rule, factValue);

            results.add(new RuleResult(
                    rule.getRuleCode(),
                    rule.getRuleType(),
                    passed,
                    passed ? null : rule.getFailureMessage(),
                    factValue
            ));

            log.debug("Rule [{}] {} — fact={}, value={}, threshold={}",
                    rule.getRuleCode(), passed ? "PASSED" : "FAILED",
                    rule.getFactKey(), factValue, rule.getThresholdValue());
        }

        return results;
    }

    /**
     * Derive overall decision from individual rule results.
     */
    public EvaluationResult.Decision deriveDecision(List<RuleResult> results) {
        for (RuleResult r : results) {
            if (!r.passed() && r.ruleType() == RuleType.HARD) {
                return EvaluationResult.Decision.REJECTED;
            }
        }
        for (RuleResult r : results) {
            if (!r.passed() && r.ruleType() == RuleType.SOFT) {
                return EvaluationResult.Decision.REFER_TO_UNDERWRITER;
            }
        }
        return EvaluationResult.Decision.APPROVED;
    }

    /**
     * Compute risk grade based on credit score and FOIR.
     */
    public String computeRiskGrade(EvaluationFacts facts) {
        if (facts.creditScore() >= 750 && facts.foir().compareTo(new BigDecimal("0.35")) <= 0) {
            return "LOW";
        } else if (facts.creditScore() >= 700 && facts.foir().compareTo(new BigDecimal("0.50")) <= 0) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    // ─── Fact extraction ───

    private String extractFact(String factKey, EvaluationFacts facts) {
        return switch (factKey) {
            case "credit_score"             -> String.valueOf(facts.creditScore());
            case "has_write_off"            -> String.valueOf(facts.hasWriteOff());
            case "has_settlement"           -> String.valueOf(facts.hasSettlement());
            case "enquiries_last_6_months"  -> String.valueOf(facts.enquiriesLast6Months());
            case "active_loans_count"       -> String.valueOf(facts.activeLoansCount());
            case "monthly_income"           -> facts.monthlyIncome().toPlainString();
            case "annual_income"            -> facts.annualIncome().toPlainString();
            case "foir"                     -> facts.foir().toPlainString();
            case "loan_to_income"           -> facts.loanToIncome().toPlainString();
            case "applicant_age"            -> String.valueOf(facts.applicantAgeYears());
            case "age_at_maturity"          -> String.valueOf(facts.ageAtLoanMaturity());
            case "employment_type"          -> facts.employmentType();
            case "requested_amount"         -> facts.requestedAmount().toPlainString();
            case "requested_tenure_months"  -> String.valueOf(facts.requestedTenureMonths());
            default -> {
                log.warn("Unknown fact key: {}", factKey);
                yield "";
            }
        };
    }

    // ─── Condition evaluation ───

    private boolean evaluateCondition(Rule rule, String factValue) {
        if (factValue == null || factValue.isBlank()) return false;

        try {
            return switch (rule.getOperator()) {
                case NOT_NULL -> !factValue.isBlank();
                case EQ       -> factValue.equalsIgnoreCase(rule.getThresholdValue());
                case NEQ      -> !factValue.equalsIgnoreCase(rule.getThresholdValue());
                case IN       -> Arrays.asList(rule.getAllowedValues().split(","))
                                       .stream().map(String::trim)
                                       .anyMatch(v -> v.equalsIgnoreCase(factValue));
                case NOT_IN   -> Arrays.asList(rule.getAllowedValues().split(","))
                                       .stream().map(String::trim)
                                       .noneMatch(v -> v.equalsIgnoreCase(factValue));
                case GTE, GT, LTE, LT, BETWEEN -> evaluateNumeric(rule, factValue);
            };
        } catch (Exception e) {
            log.warn("Error evaluating rule {}: fact={}, error={}", rule.getRuleCode(), factValue, e.getMessage());
            return false;
        }
    }

    private boolean evaluateNumeric(Rule rule, String factValue) {
        // Boolean facts expressed as 0/1 for numeric comparison
        String val = "true".equalsIgnoreCase(factValue) ? "1"
                   : "false".equalsIgnoreCase(factValue) ? "0" : factValue;

        BigDecimal fact      = new BigDecimal(val);
        BigDecimal threshold = new BigDecimal(rule.getThresholdValue());

        return switch (rule.getOperator()) {
            case GTE     -> fact.compareTo(threshold) >= 0;
            case GT      -> fact.compareTo(threshold) > 0;
            case LTE     -> fact.compareTo(threshold) <= 0;
            case LT      -> fact.compareTo(threshold) < 0;
            case BETWEEN -> {
                BigDecimal max = new BigDecimal(rule.getThresholdValueMax());
                yield fact.compareTo(threshold) >= 0 && fact.compareTo(max) <= 0;
            }
            default -> false;
        };
    }
}
