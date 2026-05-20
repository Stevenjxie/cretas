package com.cretas.aims.entity.enums;

/**
 * 商机阶段 enum — Sprint 7 wave 2 Track T4 (2026-05-20).
 *
 * <p>HJ Round 13 §15 finding: 大客户 b2b 销售商机 8 阶段 funnel.
 * F006 销售场景 + 大客户 onboarding 必上.
 *
 * <p>状态转换矩阵 (per spec):
 * <pre>
 *   LEAD       (10%)  → QUALIFIED
 *   QUALIFIED  (30%)  → DEMO
 *   DEMO       (50%)  → PROPOSAL
 *   PROPOSAL   (70%)  → NEGOTIATE
 *   NEGOTIATE  (85%)  → VERBAL
 *   VERBAL     (95%)  → CLOSED_WON / CLOSED_LOST
 *   CLOSED_WON (100%) → 终态
 *   CLOSED_LOST (0%)  → 终态
 * </pre>
 *
 * <p>Backward 流转允许 (e.g. PROPOSAL→QUALIFIED 退回再资格化, 必填 reason).
 * Forward skip 需要 explicit confirm flag (e.g. LEAD→PROPOSAL).
 * 终态 CLOSED_WON / CLOSED_LOST 任意转其他状态 = 重新激活 (审计).
 */
public enum OpportunityStage {
    LEAD("线索", 10),
    QUALIFIED("已资格化", 30),
    DEMO("产品演示", 50),
    PROPOSAL("方案报价", 70),
    NEGOTIATE("商务谈判", 85),
    VERBAL("口头承诺", 95),
    CLOSED_WON("赢单", 100),
    CLOSED_LOST("丢单", 0);

    private final String displayName;
    /** Default probability percentage (manual override allowed via API). */
    private final int defaultProbability;

    OpportunityStage(String displayName, int defaultProbability) {
        this.displayName = displayName;
        this.defaultProbability = defaultProbability;
    }

    public String getDisplayName() { return displayName; }
    public int getDefaultProbability() { return defaultProbability; }

    /**
     * 顺序值 — 用于判断 forward/backward/skip-forward.
     * LEAD=0, QUALIFIED=1, ..., VERBAL=5, CLOSED_WON=6, CLOSED_LOST=7 (parallel).
     */
    public int ordinalForFlow() {
        switch (this) {
            case LEAD: return 0;
            case QUALIFIED: return 1;
            case DEMO: return 2;
            case PROPOSAL: return 3;
            case NEGOTIATE: return 4;
            case VERBAL: return 5;
            case CLOSED_WON: return 6;
            case CLOSED_LOST: return 6;  // parallel terminal state
            default: return -1;
        }
    }

    public boolean isTerminal() {
        return this == CLOSED_WON || this == CLOSED_LOST;
    }
}
