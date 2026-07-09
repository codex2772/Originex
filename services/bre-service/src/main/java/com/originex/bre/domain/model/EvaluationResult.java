package com.originex.bre.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * EvaluationResult — the complete output of a BRE evaluation.
 *
 * <p>Contains:
 * <ul>
 *   <li>Decision: APPROVED, REJECTED, REFER_TO_UNDERWRITER</li>
 *   <li>Rule-level results — which rules passed/failed and why</li>
 *   <li>Offer parameters — if decision is APPROVED or REFER</li>
 *   <li>Risk grade — LOW, MEDIUM, HIGH</li>
 * </ul>
 */
public class EvaluationResult {

    private UUID evaluationId;
    private UUID tenantId;
    private String applicationId;
    private Decision decision;
    private String riskGrade;           // LOW, MEDIUM, HIGH
    private List<RuleResult> ruleResults = new ArrayList<>();

    // Offer parameters (populated when decision = APPROVED or REFER)
    private BigDecimal approvedAmount;
    private BigDecimal interestRate;    // Annual rate %
    private int approvedTenureMonths;
    private BigDecimal emi;
    private BigDecimal processingFeeRate; // % of approved amount
    private BigDecimal apr;

    private String summary;            // Human-readable decision summary
    private Instant evaluatedAt;

    public static EvaluationResult approved(UUID tenantId, String applicationId,
                                             BigDecimal amount, BigDecimal rate,
                                             int tenure, BigDecimal emi,
                                             BigDecimal processingFee, BigDecimal apr,
                                             String riskGrade) {
        EvaluationResult r = new EvaluationResult();
        r.evaluationId = UUID.randomUUID();
        r.tenantId = tenantId;
        r.applicationId = applicationId;
        r.decision = Decision.APPROVED;
        r.riskGrade = riskGrade;
        r.approvedAmount = amount;
        r.interestRate = rate;
        r.approvedTenureMonths = tenure;
        r.emi = emi;
        r.processingFeeRate = processingFee;
        r.apr = apr;
        r.evaluatedAt = Instant.now();
        return r;
    }

    public static EvaluationResult rejected(UUID tenantId, String applicationId,
                                             String reason) {
        EvaluationResult r = new EvaluationResult();
        r.evaluationId = UUID.randomUUID();
        r.tenantId = tenantId;
        r.applicationId = applicationId;
        r.decision = Decision.REJECTED;
        r.riskGrade = "HIGH";
        r.summary = reason;
        r.evaluatedAt = Instant.now();
        return r;
    }

    public static EvaluationResult referToUnderwriter(UUID tenantId, String applicationId,
                                                       BigDecimal suggestedAmount, BigDecimal rate,
                                                       int tenure, BigDecimal emi, String reason) {
        EvaluationResult r = new EvaluationResult();
        r.evaluationId = UUID.randomUUID();
        r.tenantId = tenantId;
        r.applicationId = applicationId;
        r.decision = Decision.REFER_TO_UNDERWRITER;
        r.riskGrade = "MEDIUM";
        r.approvedAmount = suggestedAmount;
        r.interestRate = rate;
        r.approvedTenureMonths = tenure;
        r.emi = emi;
        r.summary = reason;
        r.evaluatedAt = Instant.now();
        return r;
    }

    public void addRuleResult(RuleResult result) { this.ruleResults.add(result); }

    // Accessors
    public UUID getEvaluationId() { return evaluationId; }
    public UUID getTenantId() { return tenantId; }
    public String getApplicationId() { return applicationId; }
    public Decision getDecision() { return decision; }
    public String getRiskGrade() { return riskGrade; }
    public List<RuleResult> getRuleResults() { return ruleResults; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public BigDecimal getInterestRate() { return interestRate; }
    public int getApprovedTenureMonths() { return approvedTenureMonths; }
    public BigDecimal getEmi() { return emi; }
    public BigDecimal getProcessingFeeRate() { return processingFeeRate; }
    public BigDecimal getApr() { return apr; }
    public String getSummary() { return summary; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setSummary(String s) { this.summary = s; }
    public void setRiskGrade(String s) { this.riskGrade = s; }

    public boolean isApproved() { return decision == Decision.APPROVED; }
    public boolean isRejected() { return decision == Decision.REJECTED; }

    public enum Decision { APPROVED, REJECTED, REFER_TO_UNDERWRITER }

    /**
     * Per-rule evaluation result for audit trail.
     */
    public record RuleResult(
            String ruleCode,
            Rule.RuleType ruleType,
            boolean passed,
            String failureMessage,
            String factValue        // Actual value that was evaluated
    ) {}
}
