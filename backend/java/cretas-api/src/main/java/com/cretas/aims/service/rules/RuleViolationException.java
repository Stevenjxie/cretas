package com.cretas.aims.service.rules;

/**
 * Thrown by RuleEvaluateAspect when a matched rule has actionType=REJECT.
 *
 * Spring @ControllerAdvice maps this to HTTP 400 via {@code ApiResponse.errorWithCode()}:
 *   {"success":false, "code":400, "errorCode":"RULE_VIOLATION", "message":"<reason>",
 *    "actionHint":"<from rule.actionConfigJson>", "severity":"warning"}
 *
 * Per fool-proof Rule 5 (dead-end → next action): admin-configured {@code actionHint}
 * (e.g. a follow-up URL or "/customer/list?status=active") propagates from the rule's
 * actionConfigJson all the way to the FE, so the user can fix the dead-end inline.
 *
 * @author Cretas Team
 * @version 1.1.0  (Phase 4a post-review: actionHint + severity propagation)
 * @since 2026-05-18
 */
public class RuleViolationException extends RuntimeException {

    private final String ruleCode;
    private final String reason;
    private final String actionHint;
    private final String severity;

    /**
     * Full constructor — preferred. Per Phase 4a post-review Critical C1, actionHint and severity
     * must propagate from {@code rule.actionConfigJson} to the FE response body.
     *
     * @param ruleCode   business rule code (e.g. "po_blacklist") — surfaced as errorCode prefix
     * @param reason     user-visible reason text (Chinese) from {@code actionConfigJson.reason}
     * @param actionHint optional next-action hint (URL or short instruction) from
     *                   {@code actionConfigJson.actionHint}. May be {@code null}.
     * @param severity   "warning" / "error" / "blocking" from {@code actionConfigJson.severity}.
     *                   May be {@code null} (handler defaults to "warning").
     */
    public RuleViolationException(String ruleCode, String reason, String actionHint, String severity) {
        super("Rule '" + ruleCode + "' rejected: " + reason);
        this.ruleCode = ruleCode;
        this.reason = reason;
        this.actionHint = actionHint;
        this.severity = severity;
    }

    /**
     * Legacy 2-arg constructor for backward compatibility. Use the 4-arg form to propagate
     * admin-configured actionHint + severity per Phase 4a post-review.
     */
    public RuleViolationException(String ruleCode, String reason) {
        this(ruleCode, reason, null, null);
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getReason() {
        return reason;
    }

    public String getActionHint() {
        return actionHint;
    }

    public String getSeverity() {
        return severity;
    }
}
