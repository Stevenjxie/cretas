package com.cretas.aims.entity.rules;

/**
 * Canvas-Rules action type enum.
 *
 * Determines what RuleEngine does when a rule's SpEL condition evaluates true.
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §2.2
 *
 * Semantics:
 *  - LOG: append to rule_execution_logs, no side effect on inputObject
 *  - REJECT: throw RuleViolationException → HTTP 400, SHORT-CIRCUITS later rules
 *  - MODIFY: reflectively set field(s) on inputObject before Service body runs
 *  - TRIGGER_WORKFLOW: call WorkflowEngineFacade.startWorkflow (Phase 1 dep)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
public enum RuleActionType {
    LOG,
    REJECT,
    MODIFY,
    TRIGGER_WORKFLOW
}
