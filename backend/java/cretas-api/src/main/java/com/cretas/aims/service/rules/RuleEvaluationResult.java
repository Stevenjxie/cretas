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
 * <p>Phase 4a post-review (2026-05-19): {@code rejectActionHint} + {@code rejectSeverity} added
 * to surface admin-configured fool-proof Rule 5 hints from {@code actionConfigJson} to the FE.
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-05-18
 */
public record RuleEvaluationResult(
        boolean shouldReject,
        String rejectReason,
        String rejectRuleCode,
        String rejectActionHint,
        String rejectSeverity,
        List<RuleModification> modifications,
        List<UUID> executedRules) {

    /** Convenience factory for "no rules matched / all passed". */
    public static RuleEvaluationResult ok(List<UUID> executedRules) {
        return new RuleEvaluationResult(false, null, null, null, null, List.of(), executedRules);
    }

    /** Convenience factory for a REJECT short-circuit (legacy — no actionHint/severity). */
    public static RuleEvaluationResult reject(String ruleCode, String reason, List<UUID> executedRules) {
        return reject(ruleCode, reason, null, null, executedRules);
    }

    /** Convenience factory for a REJECT short-circuit with actionHint + severity. */
    public static RuleEvaluationResult reject(String ruleCode, String reason,
                                              String actionHint, String severity,
                                              List<UUID> executedRules) {
        return new RuleEvaluationResult(true, reason, ruleCode, actionHint, severity,
                List.of(), executedRules);
    }
}
