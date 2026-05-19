package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 邮件 sender — Phase 3 Canvas-Notify Step T4 skeleton.
 *
 * <p>Sister 实施: 集成 Spring Mail (pom.xml 加 spring-boot-starter-mail). 配置:
 * spring.mail.host / port / username / password 进 .env.prod. recipient 解析:
 * user.email 字段 (User entity 已有).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class EmailSender implements NotifySender {

    @Override
    public NotifyResult send(NotifyRequest request) {
        throw new UnsupportedOperationException(
                "EmailSender skeleton — sister chat 实施时集成 Spring Mail (JavaMailSender)");
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.EMAIL;
    }
}
