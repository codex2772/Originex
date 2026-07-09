package com.originex.bre.adapter.out.persistence;

import com.originex.bre.application.port.out.RuleRepository;
import com.originex.bre.domain.model.Rule;
import com.originex.bre.domain.model.RuleSet;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RulePersistenceAdapter implements RuleRepository {

    private final RuleSetJpaRepository ruleSetRepo;
    private final RuleJpaRepository ruleRepo;

    public RulePersistenceAdapter(RuleSetJpaRepository ruleSetRepo, RuleJpaRepository ruleRepo) {
        this.ruleSetRepo = ruleSetRepo;
        this.ruleRepo = ruleRepo;
    }

    @Override
    public Optional<RuleSet> findRuleSet(UUID tenantId, String productCode, String employmentType) {
        return ruleSetRepo.findMatchingRuleSets(tenantId, productCode, employmentType)
                .stream().findFirst().map(RuleSetJpaEntity::toDomain);
    }

    @Override
    public Optional<RuleSet> findDefaultRuleSet(UUID tenantId) {
        return ruleSetRepo.findDefaultRuleSet(tenantId).map(RuleSetJpaEntity::toDomain);
    }

    @Override
    public List<Rule> findActiveRules(UUID ruleSetId) {
        return ruleRepo.findActiveByRuleSetId(ruleSetId)
                .stream().map(RuleJpaEntity::toDomain).toList();
    }

    @Override
    public RuleSet saveRuleSet(RuleSet ruleSet) {
        return ruleSetRepo.save(RuleSetJpaEntity.fromDomain(ruleSet)).toDomain();
    }

    @Override
    public Rule saveRule(Rule rule) {
        return ruleRepo.save(RuleJpaEntity.fromDomain(rule)).toDomain();
    }
}
