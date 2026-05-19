package com.cretas.aims.service.rules;

/**
 * Thrown by RuleEvaluateAspect when a matched rule has actionType=REJECT.
 *
 * Spring @ControllerAdvice maps this to HTTP 400 with:
 *   {"success":false, "code":"RULE_VIOLATION", "message":"<reason>", "actionHint":"..."}
 *
 * Sister chat impl note: extend ControllerAdvice to handle this exception type per
 * fool-proof Rule 5 (dead-end → next action). Default mapping may already be sufficient
 * via existing BusinessException handler — verify during impl.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
public class RuleViolationException extends RuntimeException {

    private final String ruleCode;
    private final String reason;

    public RuleViolationException(String ruleCode, String reason) {
        super("Rule '" + ruleCode + "' rejected: " + reason);
        this.ruleCode = ruleCode;
        this.reason = reason;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getReason() {
        return reason;
    }
}
