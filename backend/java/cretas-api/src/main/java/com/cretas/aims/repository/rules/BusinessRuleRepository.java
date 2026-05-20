package com.cretas.aims.repository.rules;

import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // ==================== AUD-9 P3: Pageable variants ====================
    // Controller GET /canvas-rules previously returned plain List<BusinessRule>, ignoring
    // page/size/sort query params. These Pageable variants honor pagination and sort,
    // matching Page<T> contract used by /pricing/strategies/logs etc.

    /** Page variant of findByFactoryIdOrderByPriorityAsc — sort governed by Pageable. */
    Page<BusinessRule> findByFactoryId(String factoryId, Pageable pageable);

    /** Page variant — filter by scope. */
    Page<BusinessRule> findByFactoryIdAndScope(String factoryId, RuleScope scope, Pageable pageable);

    /** Page variant — filter by enabled only. */
    Page<BusinessRule> findByFactoryIdAndEnabled(String factoryId, Boolean enabled, Pageable pageable);

    /** Page variant — filter by scope + enabled. */
    Page<BusinessRule> findByFactoryIdAndScopeAndEnabled(
            String factoryId, RuleScope scope, Boolean enabled, Pageable pageable);
}
