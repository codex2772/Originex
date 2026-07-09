package com.originex.bre.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Rule — a single evaluatable condition in the BRE.
 *
 * <p>Each rule evaluates one fact (e.g. credit_score) against one
 * operator (GTE, LTE, BETWEEN, IN, NOT_NULL) and a threshold value.
 *
 * <p>Rules belong to a {@link RuleSet} and are evaluated in priority order.
 * A rule can be:
 * <ul>
 *   <li>HARD — failure immediately rejects the application (e.g. credit score < 600)</li>
 *   <li>SOFT — failure refers application to underwriter for manual review</li>
 *   <li>ADVISORY — failure adds a warning but does not block</li>
 * </ul>
 *
 * <p>Rule parameters are stored as strings and cast at evaluation time
 * to preserve database flexibility and allow per-tenant overrides.
 */
public class Rule {

    private UUID ruleId;
    private UUID tenantId;
    private UUID ruleSetId;
    private String ruleCode;            // e.g. MIN_CREDIT_SCORE, MAX_FOIR, MIN_AGE
    private String description;
    private RuleType ruleType;          // HARD, SOFT, ADVISORY
    private RuleCategory category;      // CREDIT, INCOME, AGE, PRODUCT, EMPLOYMENT, DELINQUENCY
    private String factKey;             // Field from EvaluationFacts to evaluate
    private RuleOperator operator;      // GTE, LTE, GT, LT, EQ, NEQ, BETWEEN, NOT_NULL, IN
    private String thresholdValue;      // Primary threshold (cast at runtime)
    private String thresholdValueMax;   // Upper bound for BETWEEN operator
    private String allowedValues;       // Comma-separated for IN operator
    private String failureMessage;      // Human-readable reason shown on rejection
    private int priority;               // Lower = evaluated first
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    // ─── Accessors ───
    public UUID getRuleId() { return ruleId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getRuleSetId() { return ruleSetId; }
    public String getRuleCode() { return ruleCode; }
    public String getDescription() { return description; }
    public RuleType getRuleType() { return ruleType; }
    public RuleCategory getCategory() { return category; }
    public String getFactKey() { return factKey; }
    public RuleOperator getOperator() { return operator; }
    public String getThresholdValue() { return thresholdValue; }
    public String getThresholdValueMax() { return thresholdValueMax; }
    public String getAllowedValues() { return allowedValues; }
    public String getFailureMessage() { return failureMessage; }
    public int getPriority() { return priority; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Rule() {}
    public void setRuleId(UUID id) { this.ruleId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setRuleSetId(UUID id) { this.ruleSetId = id; }
    public void setRuleCode(String s) { this.ruleCode = s; }
    public void setDescription(String s) { this.description = s; }
    public void setRuleType(RuleType t) { this.ruleType = t; }
    public void setCategory(RuleCategory c) { this.category = c; }
    public void setFactKey(String s) { this.factKey = s; }
    public void setOperator(RuleOperator o) { this.operator = o; }
    public void setThresholdValue(String s) { this.thresholdValue = s; }
    public void setThresholdValueMax(String s) { this.thresholdValueMax = s; }
    public void setAllowedValues(String s) { this.allowedValues = s; }
    public void setFailureMessage(String s) { this.failureMessage = s; }
    public void setPriority(int i) { this.priority = i; }
    public void setActive(boolean b) { this.active = b; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }

    public enum RuleType       { HARD, SOFT, ADVISORY }
    public enum RuleCategory   { CREDIT, INCOME, AGE, PRODUCT, EMPLOYMENT, DELINQUENCY, LOAN_AMOUNT }
    public enum RuleOperator   { GTE, GT, LTE, LT, EQ, NEQ, BETWEEN, NOT_NULL, IN, NOT_IN }
}
