package com.cretas.aims.service.rules;

import com.cretas.aims.entity.rules.RuleScope;

import java.util.UUID;

/**
 * Canvas-Rules engine interface.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2
 *
 * Called by:
 *  - RuleEvaluateAspect (@Around on @RuleEvaluate-annotated Service methods)
 *  - RuleTestEvaluateTool (AI dry-run, no side effects)
 *  - BusinessRuleController POST /{id}/test-evaluate
 *
 * Skeleton: impl methods throw UnsupportedOperationException. Sister chat implements.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
public interface RuleEngine {

    /**
     * Evaluate all active rules for (factoryId, scope) in priority order against inputObject.
     *
     * MODIFY actions mutate inputObject in place (caller observes changes).
     * REJECT actions populate result.shouldReject=true and short-circuit later rules.
     * LOG / TRIGGER_WORKFLOW are recorded but do not short-circuit.
     *
     * Every matched rule writes a row to rule_execution_logs (audit trail).
     *
     * @param factoryId   tenant
     * @param scope       rule scope (ORDER / INVENTORY / CUSTOMER / CUSTOM)
     * @param inputObject root object exposed to SpEL evaluator (typically the @RequestBody DTO)
     * @return aggregate result with shouldReject flag + modifications list
     */
    RuleEvaluationResult evaluate(String factoryId, RuleScope scope, Object inputObject);

    /**
     * Dry-run a single rule against sample input.
     *
     * Does NOT mutate inputObject, does NOT log to rule_execution_logs, does NOT trigger
     * downstream workflows. Used by Canvas UI "测试评估" button and by AI rule_test_evaluate Tool.
     *
     * @return true if rule condition matched (action would have applied)
     */
    boolean preview(String factoryId, UUID ruleId, Object inputObject);
}
