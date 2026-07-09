package com.originex.bre.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RuleSet — a named, ordered collection of {@link Rule}s that applies
 * to a specific product + employment type combination.
 *
 * <p>A tenant can have multiple rule sets:
 * <ul>
 *   <li>PERSONAL_LOAN_SALARIED — rules for salaried personal loan applicants</li>
 *   <li>PERSONAL_LOAN_SELF_EMPLOYED — rules for self-employed applicants</li>
 *   <li>HOME_LOAN_SALARIED — rules for home loan applicants</li>
 *   <li>DEFAULT — fallback if no specific rule set matches</li>
 * </ul>
 */
public class RuleSet {

    private UUID ruleSetId;
    private UUID tenantId;
    private String ruleSetCode;       // e.g. PERSONAL_LOAN_SALARIED
    private String productCode;       // null = applies to all products
    private String employmentType;    // null = applies to all employment types
    private String description;
    private boolean active;
    private List<Rule> rules = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    // Accessors
    public UUID getRuleSetId() { return ruleSetId; }
    public UUID getTenantId() { return tenantId; }
    public String getRuleSetCode() { return ruleSetCode; }
    public String getProductCode() { return productCode; }
    public String getEmploymentType() { return employmentType; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public List<Rule> getRules() { return rules; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public RuleSet() {}
    public void setRuleSetId(UUID id) { this.ruleSetId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setRuleSetCode(String s) { this.ruleSetCode = s; }
    public void setProductCode(String s) { this.productCode = s; }
    public void setEmploymentType(String s) { this.employmentType = s; }
    public void setDescription(String s) { this.description = s; }
    public void setActive(boolean b) { this.active = b; }
    public void setRules(List<Rule> l) { this.rules = l; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }
}
