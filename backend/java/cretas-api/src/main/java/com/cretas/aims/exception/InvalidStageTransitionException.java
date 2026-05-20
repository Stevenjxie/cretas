package com.cretas.aims.exception;

import com.cretas.aims.entity.enums.OpportunityStage;

/**
 * 非法商机阶段转换异常 — Sprint 7 wave 2 Track T4 (2026-05-20).
 *
 * <p>Triggered when:
 * <ul>
 *   <li>Skip-forward without explicit confirm flag (e.g. LEAD→PROPOSAL).</li>
 *   <li>Stage 不存在 (enum 值非法).</li>
 *   <li>试图把 closed 状态转回 non-terminal 且 reason 为空 (审计强约束).</li>
 * </ul>
 *
 * <p>Backward transitions (e.g. PROPOSAL→QUALIFIED) are ALLOWED but require non-empty reason.
 *
 * <p>HTTP status: 400 Bad Request.
 */
public class InvalidStageTransitionException extends BusinessException {

    public InvalidStageTransitionException(String message) {
        super(400, message);
    }

    public InvalidStageTransitionException(OpportunityStage from, OpportunityStage to, String reason) {
        super(400, String.format("非法商机阶段转换: %s → %s. %s",
                from != null ? from.getDisplayName() : "null",
                to != null ? to.getDisplayName() : "null",
                reason != null ? reason : ""));
    }
}
