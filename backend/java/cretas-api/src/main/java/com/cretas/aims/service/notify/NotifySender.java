package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyChannel;

/**
 * 通知渠道 sender 接口 — Phase 3 Canvas-Notify Step T4.
 *
 * <p>每个渠道一个 @Component 实现 (WeChatSender / DingTalkSender / EmailSender /
 * SmsSender / InAppSender). Spring 自动收集 List&lt;NotifySender&gt; 注入到 dispatcher
 * (sister 实施), 按 {@link #supports} 路由.
 *
 * <p>本 PR 是 skeleton — {@link #send} 在所有 impl 中抛 UnsupportedOperationException.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
public interface NotifySender {

    /**
     * 发送通知.
     *
     * @param request 通知请求 (含 templateCode / params / recipient 等)
     * @return 发送结果 (SENT / FAILED + errorMsg)
     */
    NotifyResult send(NotifyRequest request);

    /**
     * 此 sender 是否支持给定 channel.
     *
     * @param channel 通知渠道
     * @return true 表示此 sender 能处理该 channel
     */
    boolean supports(NotifyChannel channel);
}
