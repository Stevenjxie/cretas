package com.cretas.aims.entity.alerts;

/**
 * 告警事件状态机.
 *
 * <pre>
 *   OPEN ──(用户 ack)──→ ACKNOWLEDGED ──(用户/系统 resolve)──→ RESOLVED
 *      └───────────────(直接 resolve, e.g. auto-clear)──────────────┘
 * </pre>
 *
 * <ul>
 *   <li>{@code OPEN} — 新事件, 待处理</li>
 *   <li>{@code ACKNOWLEDGED} — 用户已确认看到, 但问题未消解</li>
 *   <li>{@code RESOLVED} — 问题已解决 (e.g. 库存补回 / 订单审批完成)</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
public enum AlertEventStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
