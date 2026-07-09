package com.originex.bre.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bre_rule_sets")
public class RuleSetJpaEntity {

    @Id @Column(name = "rule_set_id") private UUID ruleSetId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "rule_set_code", nullable = false) private String ruleSetCode;
    @Column(name = "product_code") private String productCode;
    @Column(name = "employment_type") private String employmentType;
    @Column(name = "description") private String description;
    @Column(name = "active") private boolean active;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    public static RuleSetJpaEntity fromDomain(com.originex.bre.domain.model.RuleSet rs) {
        RuleSetJpaEntity e = new RuleSetJpaEntity();
        e.ruleSetId = rs.getRuleSetId();
        e.tenantId = rs.getTenantId();
        e.ruleSetCode = rs.getRuleSetCode();
        e.productCode = rs.getProductCode();
        e.employmentType = rs.getEmploymentType();
        e.description = rs.getDescription();
        e.active = rs.isActive();
        e.createdAt = rs.getCreatedAt() != null ? rs.getCreatedAt() : Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public com.originex.bre.domain.model.RuleSet toDomain() {
        com.originex.bre.domain.model.RuleSet rs = new com.originex.bre.domain.model.RuleSet();
        rs.setRuleSetId(ruleSetId); rs.setTenantId(tenantId); rs.setRuleSetCode(ruleSetCode);
        rs.setProductCode(productCode); rs.setEmploymentType(employmentType);
        rs.setDescription(description); rs.setActive(active);
        rs.setCreatedAt(createdAt); rs.setUpdatedAt(updatedAt);
        return rs;
    }

    public UUID getRuleSetId() { return ruleSetId; }

    protected RuleSetJpaEntity() {}
}
