package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyAuditException;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import com.cretas.aims.service.notify.TemplateEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 邮件 sender — Canvas-Notify Phase 3 follow-up (issue #41 partial close).
 *
 * <p>Replaces the Phase 3 skeleton stub with real Spring Mail integration:
 * <ol>
 *   <li>Loads {@link NotifyTemplate} by (factoryId, templateCode)</li>
 *   <li>Renders title + body via {@link TemplateEngine} ({@code {{var}}} substitution)</li>
 *   <li>Resolves recipient {@code User.email} via {@link UserRepository}</li>
 *   <li>Sends {@link SimpleMailMessage} via {@link JavaMailSender}</li>
 *   <li>Writes per-recipient {@link NotifyLog} (SENT / FAILED + errorMsg)</li>
 * </ol>
 *
 * <p><b>MOCK mode</b>: if {@code spring.mail.host} or {@code spring.mail.username} is
 * blank (typical in dev / test envs without SMTP credentials), {@code send()}
 * skips actual SMTP and writes audit rows with status SENT + errorMsg="[MOCK]...".
 * This lets test environments exercise the wiring without real credentials, while
 * the audit trail clearly shows mock-mode rows for ops.
 *
 * <p><b>Per-recipient error isolation</b>: missing email / SMTP exception for one
 * recipient does not abort the whole fan-out — that recipient gets a FAILED log
 * row, others continue. The aggregate {@link NotifyResult} is SENT if &ge;1
 * succeeded, FAILED if all failed.
 *
 * @since 2026-05-21 (Phase 3 follow-up — replace stub with real impl)
 */
@Slf4j
@Component
public class EmailSender implements NotifySender {

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private NotifyTemplateRepository templateRepository;

    @Autowired
    private NotifyLogRepository logRepository;

    @Autowired
    private TemplateEngine templateEngine;

    @Autowired
    private UserRepository userRepository;

    @Value("${spring.mail.host:}")
    private String smtpHost;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${cretas.notify.email.from:notify@cretas.example}")
    private String fromAddress;

    @Override
    public NotifyResult send(NotifyRequest request) {
        if (request == null) {
            return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED, "request 为空");
        }

        String factoryId = request.factoryId();
        String templateCode = request.templateCode();
        List<Long> recipients = request.recipientUserIds();

        // 1. 加载 template (factory-scoped)
        Optional<NotifyTemplate> templateOpt =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (templateOpt.isEmpty()) {
            String errMsg = "通知模板不存在: factoryId=" + factoryId + ", templateCode=" + templateCode;
            log.warn("[EmailSender] {}", errMsg);
            writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED, errMsg);
        }
        NotifyTemplate template = templateOpt.get();

        // 2. 渲染 title + body
        String renderedTitle;
        String renderedBody;
        try {
            renderedTitle = templateEngine.render(template.getTitle(), request.params());
            renderedBody = templateEngine.render(template.getBodyTemplate(), request.params());
        } catch (IllegalArgumentException e) {
            String errMsg = "模板渲染失败: " + e.getMessage();
            log.warn("[EmailSender] {}", errMsg);
            if (recipients != null) {
                for (Long uid : recipients) {
                    writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
                }
            } else {
                writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            }
            return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED, errMsg);
        }

        // 3. recipients 必填 (邮件没有"全工厂广播", 必须有具体地址)
        if (recipients == null || recipients.isEmpty()) {
            String errMsg = "EMAIL 渠道未提供 recipientUserIds (邮件需要明确收件人)";
            log.warn("[EmailSender] {}", errMsg);
            writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED, errMsg);
        }

        // 4. 检查 SMTP 配置 — 空 → MOCK 模式
        boolean mockMode = isBlank(smtpHost) || isBlank(smtpUsername);
        if (mockMode) {
            log.warn("[EmailSender] SMTP 未配置 (host='{}', username='{}'), 进入 MOCK 模式 — "
                    + "audit 行将标 SENT 但实际不发邮件. Set MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD 启用真发.",
                    smtpHost, smtpUsername);
        }

        // 5. 逐 recipient 发送 + 写 audit
        int sentCount = 0;
        int failedCount = 0;
        String firstError = null;
        for (Long uid : recipients) {
            String result = sendOne(uid, renderedTitle, renderedBody, mockMode);
            if (result == null) {
                writeLog(factoryId, templateCode, uid, NotifyStatus.SENT,
                        mockMode ? "[MOCK] SMTP 未配置, 未实际发邮件" : null);
                sentCount++;
            } else {
                writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, result);
                failedCount++;
                if (firstError == null) firstError = result;
            }
        }

        log.info("[EmailSender] EMAIL 发送完成: factoryId={}, templateCode={}, "
                + "recipientCount={}, sentCount={}, failedCount={}, mockMode={}",
                factoryId, templateCode, recipients.size(), sentCount, failedCount, mockMode);

        // 聚合 result: 全失败 → FAILED, 否则 SENT (含部分成功, errorMsg 附首个错误)
        if (sentCount == 0) {
            return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.FAILED,
                    "全部 " + failedCount + " 个收件人发送失败. 首个错误: " + firstError);
        }
        String partialErr = failedCount > 0
                ? failedCount + "/" + recipients.size() + " 个收件人失败 (详见 NotifyLog)"
                : null;
        return new NotifyResult(NotifyChannel.EMAIL, NotifyStatus.SENT, partialErr);
    }

    /**
     * 发送单条邮件. 成功返 null, 失败返 errorMsg.
     *
     * <p>MOCK 模式下直接返 null (不实际调 mailSender), 实环境下查 User.email + SMTP 发送.
     */
    private String sendOne(Long userId, String subject, String body, boolean mockMode) {
        // 查 User → email
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return "用户不存在: userId=" + userId;
        }
        User user = userOpt.get();
        String email = user.getEmail();
        if (isBlank(email)) {
            return "用户未设置邮箱: userId=" + userId + ", username=" + user.getUsername();
        }

        if (mockMode) {
            log.debug("[EmailSender][MOCK] would-send to {} subject='{}'", email, subject);
            return null;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(email);
            msg.setSubject(subject != null ? subject : "");
            msg.setText(body != null ? body : "");
            mailSender.send(msg);
            return null;
        } catch (MailException e) {
            log.error("[EmailSender] SMTP send failed: userId={}, email={}, err={}",
                    userId, email, e.getMessage(), e);
            return "SMTP 发送失败: " + e.getMessage();
        }
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.EMAIL;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 写 audit log. 失败 → throw NotifyAuditException 不静默 swallow.
     * Phase 3 review High #2/#3 fix — 见 {@link NotifyAuditException} javadoc.
     */
    private void writeLog(String factoryId, String templateCode, Long recipientUserId,
                          NotifyStatus status, String errorMsg) {
        NotifyLog logRow = NotifyLog.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .recipientUserId(recipientUserId)
                .channel(NotifyChannel.EMAIL)
                .status(status)
                .errorMsg(errorMsg)
                .sentAt(LocalDateTime.now())
                .build();
        try {
            logRepository.save(logRow);
        } catch (Exception e) {
            log.error(
                    "[EmailSender] Failed to write NotifyLog: factoryId={}, channel={}, recipient={}",
                    factoryId, NotifyChannel.EMAIL, recipientUserId, e);
            throw new NotifyAuditException(
                    "通知发送审计写入失败 — 请联系运维 (channel=EMAIL, recipient="
                            + recipientUserId + ")", e);
        }
    }
}
