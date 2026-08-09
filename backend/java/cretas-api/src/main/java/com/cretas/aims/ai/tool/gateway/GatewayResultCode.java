package com.cretas.aims.ai.tool.gateway;

/** Fixed, non-sensitive result vocabulary persisted by the gateway ledger. */
public enum GatewayResultCode {
    PRINCIPAL_DENIED,
    POLICY_DENIED,
    DEADLINE_EXPIRED,
    PREVIEW_UNSUPPORTED,
    CONFIRMATION_REQUIRED,
    IDEMPOTENCY_REQUIRED,
    IDEMPOTENT_REPLAY,
    IDEMPOTENCY_CONFLICT,
    IN_FLIGHT_OR_IN_DOUBT,
    CONFIRMATION_REJECTED,
    CONFIRMATION_CLAIM_UNCERTAIN,
    TOOL_SUCCEEDED,
    TOOL_EXECUTION_FAILED,
    TOOL_NEEDS_INFO,
    /**
     * 工具<b>明确拒绝</b>，且拒绝时什么都没做。
     *
     * <p>与 {@link #TOOL_OUTCOME_UNCERTAIN} 的区别是承重的：后者表示「不知道写没写，
     * 需要人工对账」；这个表示「结构上确定没有写入」。把前者用在后者身上，
     * 会把一个干净的拒绝记成疑似写入的脏账。
     *
     * <p>2026-08-09 引入。此前网关只认 {@code NEED_MORE_INFO} 一种干净失败，
     * 其余一律 OUTCOME_UNKNOWN —— 于是工具说「涉及调料克数只能预览，请去产品配置页确认」，
     * 用户看到的是「执行结果需要人工对账」，提示内容还被清空。
     *
     * <p>⛔ 不许为了走这条路而让工具谎报 {@code NEED_MORE_INFO}：那是用一个不准确的
     * 状态码骗中间层，会留下将来很难查的坑。要走这条路就诚实地发 {@code DECLINED}。
     */
    TOOL_DECLINED,
    TOOL_OUTCOME_UNCERTAIN,
    CONFIRMATION_RESOLUTION_UNCERTAIN,
    PERSISTENCE_FINALIZATION_UNCERTAIN,
    TOOL_PREVIEW_SUCCEEDED,
    TOOL_PREVIEW_FAILED
}
