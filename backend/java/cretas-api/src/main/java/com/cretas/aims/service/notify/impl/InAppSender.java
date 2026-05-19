package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 站内信 sender — Phase 3 Canvas-Notify Step T4 skeleton.
 *
 * <p>Sister 实施: 写入 {@code in_app_messages} 表 (sister 加 migration), 同时通过
 * WebSocket / SSE 推送给在线用户. RN App 状态栏 + Vue web-admin Header 显示未读 badge.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class InAppSender implements NotifySender {

    @Override
    public NotifyResult send(NotifyRequest request) {
        throw new UnsupportedOperationException(
                "InAppSender skeleton — sister chat 实施时写 in_app_messages + WebSocket push");
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.IN_APP;
    }
}
