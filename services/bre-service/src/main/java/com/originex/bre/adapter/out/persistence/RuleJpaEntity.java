package com.originex.bre.adapter.out.persistence;

import com.originex.bre.domain.model.Rule;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bre_rules")
public class RuleJpaEntity {

    @Id @Column(name = "rule_id") private UUID ruleId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "rule_set_id", nullable = false) private UUID ruleSetId;
    @Column(name = "rule_code", nullable = false) private String ruleCode;
    @Column(name = "description") private String description;
    @Enumerated(EnumType.STRING) @Column(name = "rule_type", nullable = false) private Rule.RuleType ruleType;
    @Enumerated(EnumType.STRING) @Column(name = "category", nullable = false) private Rule.RuleCategory category;
    @Column(name = "fact_key", nullable = false) private String factKey;
    @Enumerated(EnumType.STRING) @Column(name = "operator", nullable = false) private Rule.RuleOperator operator;
    @Column(name = "threshold_value") private String thresholdValue;
    @Column(name = "threshold_value_max") private String thresholdValueMax;
    @Column(name = "allowed_values") private String allowedValues;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "priority") private int priority;
    @Column(name = "active") private boolean active;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    public static RuleJpaEntity fromDomain(Rule r) {
        RuleJpaEntity e = new RuleJpaEntity();
        e.ruleId = r.getRuleId();
        e.tenantId = r.getTenantId();
        e.ruleSetId = r.getRuleSetId();
        e.ruleCode = r.getRuleCode();
        e.description = r.getDescription();
        e.ruleType = r.getRuleType();
        e.category = r.getCategory();
        e.factKey = r.getFactKey();
        e.operator = r.getOperator();
        e.thresholdValue = r.getThresholdValue();
        e.thresholdValueMax = r.getThresholdValueMax();
        e.allowedValues = r.getAllowedValues();
        e.failureMessage = r.getFailureMessage();
        e.priority = r.getPriority();
        e.active = r.isActive();
        e.createdAt = r.getCreatedAt() != null ? r.getCreatedAt() : Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public Rule toDomain() {
        Rule r = new Rule();
        r.setRuleId(ruleId); r.setTenantId(tenantId); r.setRuleSetId(ruleSetId);
        r.setRuleCode(ruleCode); r.setDescription(description); r.setRuleType(ruleType);
        r.setCategory(category); r.setFactKey(factKey); r.setOperator(operator);
        r.setThresholdValue(thresholdValue); r.setThresholdValueMax(thresholdValueMax);
        r.setAllowedValues(allowedValues); r.setFailureMessage(failureMessage);
        r.setPriority(priority); r.setActive(active); r.setCreatedAt(createdAt); r.setUpdatedAt(updatedAt);
        return r;
    }

    protected RuleJpaEntity() {}
}
