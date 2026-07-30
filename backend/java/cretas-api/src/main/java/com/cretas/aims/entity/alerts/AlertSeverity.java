package com.cretas.aims.entity.alerts;

/**
 * 告警严重度 — 决定通知 UI / 推送频次 / SLA.
 *
 * <ul>
 *   <li>{@code LOW} — 仅 IN_APP 显示, 不主动推送</li>
 *   <li>{@code MID} — IN_APP + 选定渠道 (WECHAT/DINGTALK)</li>
 *   <li>{@code HIGH} — 立即推送全部启用渠道 + 升级到主管角色</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
public enum AlertSeverity {
    LOW,
    MID,
    HIGH
}
