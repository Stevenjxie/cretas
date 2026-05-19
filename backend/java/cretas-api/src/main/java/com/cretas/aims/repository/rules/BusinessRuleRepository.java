package com.cretas.aims.repository.rules;

import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BusinessRule repository.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §3
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Repository
public interface BusinessRuleRepository extends JpaRepository<BusinessRule, UUID> {

    /**
     * RuleEngine.evaluate uses this to load all active rules for a scope, in priority order.
     * Smaller priority first. REJECT rules short-circuit later ones.
     */
    List<BusinessRule> findByFactoryIdAndScopeAndEnabledTrueOrderByPriorityAsc(
            String factoryId, RuleScope scope);

    /** Used by Tools to look up by unique business key (factoryId, ruleCode). */
    Optional<BusinessRule> findByFactoryIdAndRuleCode(String factoryId, String ruleCode);

    /** Controller list endpoint — all rules for a factory, optionally filtered by scope. */
    List<BusinessRule> findByFactoryIdOrderByScopeAscPriorityAsc(String factoryId);

    List<BusinessRule> findByFactoryIdAndScopeOrderByPriorityAsc(String factoryId, RuleScope scope);

    /** Controller list endpoint — all rules ordered by priority (no scope filter). */
    List<BusinessRule> findByFactoryIdOrderByPriorityAsc(String factoryId);

    /** Controller list endpoint — filter by enabled only. */
    List<BusinessRule> findByFactoryIdAndEnabledOrderByPriorityAsc(String factoryId, Boolean enabled);

    /** Controller list endpoint — filter by scope + enabled. */
    List<BusinessRule> findByFactoryIdAndScopeAndEnabledOrderByPriorityAsc(
            String factoryId, RuleScope scope, Boolean enabled);
}
