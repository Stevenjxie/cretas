package com.cretas.aims.service.rules;

import java.util.List;
import java.util.UUID;

/**
 * Aggregate result from RuleEngine.evaluate.
 *
 * If shouldReject == true, the aspect throws RuleViolationException, suppressing the
 * Service method body. Otherwise, MODIFY rules have already mutated inputObject and the
 * method body runs normally.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
public record RuleEvaluationResult(
        boolean shouldReject,
        String rejectReason,
        String rejectRuleCode,
        List<RuleModification> modifications,
        List<UUID> executedRules) {

    /** Convenience factory for "no rules matched / all passed". */
    public static RuleEvaluationResult ok(List<UUID> executedRules) {
        return new RuleEvaluationResult(false, null, null, List.of(), executedRules);
    }

    /** Convenience factory for a REJECT short-circuit. */
    public static RuleEvaluationResult reject(String ruleCode, String reason, List<UUID> executedRules) {
        return new RuleEvaluationResult(true, reason, ruleCode, List.of(), executedRules);
    }
}
