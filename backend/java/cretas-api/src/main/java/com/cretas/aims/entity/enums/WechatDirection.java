package com.cretas.aims.entity.enums;

/**
 * 微信记录方向 enum (Sprint 6 W2-C-1, 2026-05-19).
 *
 * <p>3 种方向, 标识聊天消息的来源 / 去向:
 * <ul>
 *   <li>INBOUND: 客户 → 业务员 (客户主动发消息)</li>
 *   <li>OUTBOUND: 业务员 → 客户 (业务员主动跟进)</li>
 *   <li>INTERNAL: 内部备注 (业务员自己写的工作笔记, 不发给客户)</li>
 * </ul>
 *
 * <p>背景: HJ Round 11 §F.2 "微信记录" tab. F006 业务员场景:
 * 跟客户微信聊单进度, 手工补录到 CRM (微信 OAuth API 第三方限制, 手工补录最实际).
 *
 * <p>跟 Customer.customerStatus / 跟踪记录 trackingType 互补 — 这里专注微信会话流水.
 */
public enum WechatDirection {
    INBOUND("客户来信", "客户主动发消息给业务员"),
    OUTBOUND("我方去信", "业务员主动联系客户"),
    INTERNAL("内部备注", "业务员自记工作笔记, 不展示给客户");

    private final String displayName;
    private final String description;

    WechatDirection(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
