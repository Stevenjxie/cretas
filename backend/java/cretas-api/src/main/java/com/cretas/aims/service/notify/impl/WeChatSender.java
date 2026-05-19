package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企业微信 sender — Phase 3 Canvas-Notify Step T4 skeleton.
 *
 * <p>Sister 实施: 集成 weixin-java-mp SDK (pom.xml 加 dependency), 用应用消息或群机器人
 * webhook 发送. recipient 解析: 用 user.wechatId 或 user.phone 查企微 userid.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class WeChatSender implements NotifySender {

    @Override
    public NotifyResult send(NotifyRequest request) {
        throw new UnsupportedOperationException(
                "WeChatSender skeleton — sister chat 实施时集成 weixin-java-mp SDK");
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.WECHAT;
    }
}
