package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyStatus;

/**
 * 单 channel + 单 recipient 的发送结果 — Phase 3 Canvas-Notify Step T4.
 *
 * <p>由 {@link NotifySender#send} 返回, sister 实施时 NotifyDispatcher 收集并写 NotifyLog.
 *
 * @param channel 实际发送的渠道
 * @param status SENT (成功提交) 或 FAILED (sender 抛异常 / 渠道直接拒绝)
 * @param errorMsg FAILED 时填异常摘要, SENT 时 null
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
public record NotifyResult(
        NotifyChannel channel,
        NotifyStatus status,
        String errorMsg
) {}
