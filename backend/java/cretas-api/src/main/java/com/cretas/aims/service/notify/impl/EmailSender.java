package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件 sender — Phase 3 Canvas-Notify Step T4 (stub pending Spring Mail dep).
 *
 * <p><b>Phase 3 follow-up</b>: pom.xml 当前未含 {@code spring-boot-starter-mail}.
 * 实际邮件发送需后续 sister chat 加依赖 + 配置:
 * <ol>
 *   <li>在 {@code backend/java/cretas-api/pom.xml} 加 dependency:
 *       <pre>{@code
 *       <dependency>
 *         <groupId>org.springframework.boot</groupId>
 *         <artifactId>spring-boot-starter-mail</artifactId>
 *       </dependency>
 *       }</pre></li>
 *   <li>在 {@code .env.prod} 加 SMTP 配置:
 *       {@code SPRING_MAIL_HOST=smtp.qq.com}, {@code SPRING_MAIL_USERNAME=xxx@qq.com},
 *       {@code SPRING_MAIL_PASSWORD=xxx} (per CREDENTIAL-MANAGEMENT.md)</li>
 *   <li>本类注入 {@code JavaMailSender}, 构造 {@code MimeMessage} + {@code MimeMessageHelper}
 *       渲染 HTML body + 发送. recipient.email 字段从 {@code User} entity 取.</li>
 * </ol>
 *
 * <p>当前 stub 行为: 调用 send() 返 FAILED 状态 + 写 NotifyLog 标记 "Email channel not yet configured",
 * 避免静默 swallow 给上游错觉. fan-out 整体仍可成功 (其他 channel 不受影响, 见 NotifySenderRegistry).
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class EmailSender implements NotifySender {

    @Autowired
    private NotifyLogRepository logRepository;

    @Override
    public NotifyResult send(NotifyRequest request) {
        String errMsg = "邮件渠道暂未配置 (Phase 3 follow-up: 加 spring-boot-starter-mail 依赖 + SMTP 配置)";
        log.warn("[EmailSender] {}, factoryId={}, templateCode={}",
                errMsg, request.factoryId(), request.templateCode());

        // 写 FAILED log per recipient — 让用户/审计能看到为什么没收到邮件
        List<Long> recipients = request.recipientUserIds();
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                writeLog(request.factoryId(), request.templateCode(), uid, errMsg);
            }
        } else {
            writeLog(request.factoryId(), request.templateCode(), null, errMsg);
        }

        return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED, errMsg);
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.EMAIL;
    }

    private void writeLog(String factoryId, String templateCode, Long recipientUserId, String errMsg) {
        try {
            NotifyLog logRow = NotifyLog.builder()
                    .factoryId(factoryId)
                    .templateCode(templateCode)
                    .recipientUserId(recipientUserId)
                    .channel(NotifyChannel.EMAIL)
                    .status(NotifyStatus.FAILED)
                    .errorMsg(errMsg)
                    .sentAt(LocalDateTime.now())
                    .build();
            logRepository.save(logRow);
        } catch (Exception e) {
            log.error("[EmailSender] 写 NotifyLog 失败: {}", e.getMessage(), e);
        }
    }
}
