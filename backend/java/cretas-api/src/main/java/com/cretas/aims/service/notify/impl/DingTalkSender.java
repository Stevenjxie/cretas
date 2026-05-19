package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 钉钉 sender — Phase 3 Canvas-Notify Step T4 skeleton.
 *
 * <p>Sister 实施: 钉钉群机器人 webhook (POST text/markdown message) 或工作通知
 * (oapi.dingtalk.com/message/corpconversation/asyncsend_v2). 配置:
 * webhook URL + secret 存在 NotifyTemplate.variables_schema_json 或 factory_settings.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class DingTalkSender implements NotifySender {

    @Override
    public NotifyResult send(NotifyRequest request) {
        throw new UnsupportedOperationException(
                "DingTalkSender skeleton — sister chat 实施时调用钉钉 webhook / oapi");
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.DINGTALK;
    }
}
