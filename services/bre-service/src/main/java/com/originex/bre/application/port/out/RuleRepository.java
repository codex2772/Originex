package com.originex.bre.application.port.out;

import com.originex.bre.domain.model.Rule;
import com.originex.bre.domain.model.RuleSet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleRepository {

    /** Find best-matching rule set for a product + employment type combination */
    Optional<RuleSet> findRuleSet(UUID tenantId, String productCode, String employmentType);

    /** Find the default fallback rule set */
    Optional<RuleSet> findDefaultRuleSet(UUID tenantId);

    /** Load all active rules for a rule set */
    List<Rule> findActiveRules(UUID ruleSetId);

    RuleSet saveRuleSet(RuleSet ruleSet);
    Rule saveRule(Rule rule);
}
