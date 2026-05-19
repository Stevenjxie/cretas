package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 短信 sender — Phase 3 Canvas-Notify Step T4 skeleton.
 *
 * <p>Sister 实施: 集成 aliyun-sms SDK (pom.xml 加 dependency 或 alibabacloud-dysmsapi).
 * 配置: ALIBABA_ACCESSKEY_ID / ALIBABA_SECRET_KEY (CREDENTIAL-MANAGEMENT.md 已有),
 * 加 SMS_SIGN_NAME / SMS_TEMPLATE_CODE 注 .env.prod. recipient 解析: user.phone.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class SmsSender implements NotifySender {

    @Override
    public NotifyResult send(NotifyRequest request) {
        throw new UnsupportedOperationException(
                "SmsSender skeleton — sister chat 实施时集成 aliyun-sms SDK");
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.SMS;
    }
}
