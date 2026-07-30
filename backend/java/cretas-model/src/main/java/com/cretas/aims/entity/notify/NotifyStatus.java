package com.cretas.aims.entity.notify;

/**
 * 通知发送状态枚举 — Phase 3 Canvas-Notify.
 *
 * <p>仅二态: SENT 表示 sender 成功提交给渠道 (不保证对方真收到 — 异步回调超出 Phase 3
 * scope), FAILED 表示 sender 抛异常或渠道直接拒绝.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
public enum NotifyStatus {
    SENT,
    FAILED
}
