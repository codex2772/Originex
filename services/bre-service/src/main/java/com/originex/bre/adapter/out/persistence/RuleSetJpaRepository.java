package com.originex.bre.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleSetJpaRepository extends JpaRepository<RuleSetJpaEntity, UUID> {

    @Query("""
        SELECT rs FROM RuleSetJpaEntity rs
        WHERE rs.tenantId = :tenantId
          AND rs.active = true
          AND (rs.productCode = :productCode OR rs.productCode IS NULL)
          AND (rs.employmentType = :employmentType OR rs.employmentType IS NULL)
        ORDER BY
          CASE WHEN rs.productCode = :productCode THEN 0 ELSE 1 END,
          CASE WHEN rs.employmentType = :employmentType THEN 0 ELSE 1 END
    """)
    List<RuleSetJpaEntity> findMatchingRuleSets(UUID tenantId, String productCode, String employmentType);

    @Query("""
        SELECT rs FROM RuleSetJpaEntity rs
        WHERE rs.tenantId = :tenantId
          AND rs.ruleSetCode = 'DEFAULT'
          AND rs.active = true
    """)
    Optional<RuleSetJpaEntity> findDefaultRuleSet(UUID tenantId);
}
