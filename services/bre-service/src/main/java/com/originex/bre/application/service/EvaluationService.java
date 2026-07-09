package com.originex.bre.application.service;

import com.originex.bre.application.port.in.EvaluationUseCase;
import com.originex.bre.application.port.out.RuleRepository;
import com.originex.bre.domain.engine.OfferCalculator;
import com.originex.bre.domain.engine.RuleEvaluationEngine;
import com.originex.bre.domain.model.*;
import com.originex.bre.domain.model.EvaluationResult.Decision;
import com.originex.bre.domain.model.EvaluationResult.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BRE Application Service — orchestrates eligibility evaluation.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load applicable rule set (product + employment type match, fallback to DEFAULT)</li>
 *   <li>Compute derived facts (FOIR, loan-to-income, age at maturity)</li>
 *   <li>Run rule engine → get per-rule results</li>
 *   <li>Derive overall decision (APPROVED / REJECTED / REFER)</li>
 *   <li>If approved or refer → calculate offer (rate, EMI, APR)</li>
 *   <li>Return complete EvaluationResult</li>
 * </ol>
 */
@Service
public class EvaluationService implements EvaluationUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final RuleRepository ruleRepository;
    private final RuleEvaluationEngine evaluationEngine;
    private final OfferCalculator offerCalculator;

    public EvaluationService(RuleRepository ruleRepository,
                             RuleEvaluationEngine evaluationEngine,
                             OfferCalculator offerCalculator) {
        this.ruleRepository = ruleRepository;
        this.evaluationEngine = evaluationEngine;
        this.offerCalculator = offerCalculator;
    }

    @Override
    public EvaluationResult evaluate(EvaluateCommand cmd) {
        log.info("BRE evaluation: applicationId={}, product={}, employment={}, creditScore={}",
                cmd.applicationId(), cmd.productCode(), cmd.employmentType(), cmd.creditScore());

        // ─── Step 1: Find the matching rule set ───
        RuleSet ruleSet = ruleRepository
                .findRuleSet(cmd.tenantId(), cmd.productCode(), cmd.employmentType())
                .or(() -> ruleRepository.findDefaultRuleSet(cmd.tenantId()))
                .orElse(null);

        if (ruleSet == null) {
            log.warn("No rule set found for tenant={}, product={}, employment={}",
                    cmd.tenantId(), cmd.productCode(), cmd.employmentType());
            return EvaluationResult.referToUnderwriter(
                    cmd.tenantId(), cmd.applicationId(),
                    cmd.requestedAmount(), new BigDecimal("14.00"),
                    cmd.requestedTenureMonths(), BigDecimal.ZERO,
                    "No rule set configured for this product — refer to underwriter");
        }

        // ─── Step 2: Compute provisional EMI for FOIR calculation ───
        BigDecimal provisionalRate = new BigDecimal("14.00"); // default before credit score
        BigDecimal provisionalEmi = offerCalculator.calculateEmi(
                cmd.requestedAmount(), provisionalRate, cmd.requestedTenureMonths());

        // ─── Step 3: Build facts ───
        EvaluationFacts facts = EvaluationFacts.of(
                cmd.tenantId(), cmd.applicationId(), cmd.customerId(),
                cmd.productCode(), cmd.creditScore(), cmd.bureauName(),
                cmd.hasWriteOff(), cmd.hasSettlement(),
                cmd.enquiriesLast6Months(), cmd.activeLoansCount(),
                cmd.existingEmiObligations(), cmd.employmentType(),
                cmd.monthlyIncome(), cmd.applicantAgeYears(),
                cmd.requestedTenureMonths(), cmd.requestedAmount(),
                provisionalEmi, cmd.currency()
        );

        // ─── Step 4: Load and evaluate rules ───
        List<Rule> rules = ruleRepository.findActiveRules(ruleSet.getRuleSetId());
        List<RuleResult> ruleResults = evaluationEngine.evaluate(rules, facts);

        // ─── Step 5: Derive decision ───
        Decision decision = evaluationEngine.deriveDecision(ruleResults);
        String riskGrade = evaluationEngine.computeRiskGrade(facts);

        log.info("BRE decision: applicationId={}, decision={}, riskGrade={}, rulesEvaluated={}",
                cmd.applicationId(), decision, riskGrade, ruleResults.size());

        // ─── Step 6: Build result with offer if not rejected ───
        EvaluationResult result;

        if (decision == Decision.REJECTED) {
            String rejectionReasons = ruleResults.stream()
                    .filter(r -> !r.passed() && r.ruleType() == Rule.RuleType.HARD)
                    .map(RuleResult::failureMessage)
                    .collect(Collectors.joining("; "));

            result = EvaluationResult.rejected(cmd.tenantId(), cmd.applicationId(), rejectionReasons);

        } else {
            // Calculate actual offer
            BigDecimal rate = offerCalculator.determineRate(
                    cmd.creditScore(), riskGrade, cmd.productCode());
            BigDecimal approvedAmount = offerCalculator.determineApprovedAmount(facts, rate);
            BigDecimal emi = offerCalculator.calculateEmi(
                    approvedAmount, rate, cmd.requestedTenureMonths());
            BigDecimal processingFeeRate = offerCalculator.determineProcessingFeeRate(riskGrade);
            BigDecimal apr = offerCalculator.calculateApr(
                    approvedAmount, rate, processingFeeRate, cmd.requestedTenureMonths());

            if (decision == Decision.APPROVED) {
                result = EvaluationResult.approved(
                        cmd.tenantId(), cmd.applicationId(),
                        approvedAmount, rate, cmd.requestedTenureMonths(),
                        emi, processingFeeRate, apr, riskGrade);

                // Note if approved amount differs from requested
                if (approvedAmount.compareTo(cmd.requestedAmount()) < 0) {
                    result.setSummary(String.format(
                            "Approved for INR %s (requested: INR %s) — adjusted based on FOIR",
                            approvedAmount.toPlainString(), cmd.requestedAmount().toPlainString()));
                }
            } else {
                String softFailures = ruleResults.stream()
                        .filter(r -> !r.passed() && r.ruleType() == Rule.RuleType.SOFT)
                        .map(RuleResult::failureMessage)
                        .collect(Collectors.joining("; "));

                result = EvaluationResult.referToUnderwriter(
                        cmd.tenantId(), cmd.applicationId(),
                        approvedAmount, rate, cmd.requestedTenureMonths(), emi,
                        "Referred for manual review: " + softFailures);
            }
        }

        // Attach all rule results for audit trail
        ruleResults.forEach(result::addRuleResult);

        return result;
    }
}
