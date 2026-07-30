package com.cretas.aims.entity.enums;

/**
 * 通话类型 enum (Sprint 6 W2-C-2, 2026-05-19).
 *
 * <p>3 种类型, 标识通话方向 / 状态:
 * <ul>
 *   <li>INCOMING: 客户呼入 (客户主动打电话)</li>
 *   <li>OUTGOING: 业务员呼出 (业务员主动跟进)</li>
 *   <li>MISSED: 漏接 (无录音, durationSec 通常 0)</li>
 * </ul>
 *
 * <p>背景: HJ Round 11 §F.2 "通话记录" tab. F006 卤制品销售场景:
 * 电话沟通后上传录音 + Whisper 自动转写关键信息.
 *
 * <p>跟 WechatDirection 互补 — 该 enum 专注电话场景, WechatDirection 专注微信会话.
 */
public enum CallType {
    INCOMING("客户呼入", "客户主动打电话给业务员"),
    OUTGOING("业务员呼出", "业务员主动联系客户"),
    MISSED("漏接", "客户呼入未接通 (无录音, 时长 0)");

    private final String displayName;
    private final String description;

    CallType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
