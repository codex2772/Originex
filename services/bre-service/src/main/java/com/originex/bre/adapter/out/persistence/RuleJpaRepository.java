package com.originex.bre.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RuleJpaRepository extends JpaRepository<RuleJpaEntity, UUID> {

    @Query("SELECT r FROM RuleJpaEntity r WHERE r.ruleSetId = :ruleSetId AND r.active = true ORDER BY r.priority")
    List<RuleJpaEntity> findActiveByRuleSetId(UUID ruleSetId);
}
