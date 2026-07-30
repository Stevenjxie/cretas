package com.cretas.aims.entity.notify;

/**
 * 通知渠道枚举 — Phase 3 Canvas-Notify.
 *
 * <p>5 个渠道 fan-out 策略: 每个 {@link com.cretas.aims.service.notify.NotifySender}
 * 实现声明自己 supports 的 channel, 同一 NotifyRequest 多 channel 时并行 send.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
public enum NotifyChannel {
    /** 企业微信 — 用 weixin-java-mp SDK (sister 实施) */
    WECHAT,
    /** 钉钉 — 机器人 webhook (sister 实施) */
    DINGTALK,
    /** 邮件 — Spring Mail (sister 实施) */
    EMAIL,
    /** 短信 — aliyun-sms SDK (sister 实施) */
    SMS,
    /** 站内信 — 写 in_app_messages 表 + WebSocket push (sister 实施) */
    IN_APP
}
