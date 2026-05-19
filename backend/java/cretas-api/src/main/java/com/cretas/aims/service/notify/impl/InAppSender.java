package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyAuditException;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import com.cretas.aims.service.notify.TemplateEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 站内信 sender — Phase 3 Canvas-Notify Step T4.
 *
 * <p>当前实现: 渲染模板 + 写入 {@link NotifyLog} (status=SENT 或 FAILED). NotifyLog 行
 * 即作为站内信存储 — Canvas / RN App 后续读 NotifyLog (channel=IN_APP) 显示未读列表.
 *
 * <p>Phase 3 follow-up: 加 {@code in_app_messages} 专用表 (含 isRead / readAt) +
 * WebSocket / SSE push 在线用户. 当前 NotifyLog 已可支持基础站内信读取查询.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class InAppSender implements NotifySender {

    @Autowired
    private NotifyTemplateRepository templateRepository;

    @Autowired
    private NotifyLogRepository logRepository;

    @Autowired
    private TemplateEngine templateEngine;

    @Override
    public NotifyResult send(NotifyRequest request) {
        if (request == null) {
            return new NotifyResult(NotifyChannel.IN_APP, NotifyStatus.FAILED, "request 为空");
        }

        String factoryId = request.factoryId();
        String templateCode = request.templateCode();
        List<Long> recipients = request.recipientUserIds();

        // 1. 加载 template (factory-scoped)
        Optional<NotifyTemplate> templateOpt =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (templateOpt.isEmpty()) {
            String errMsg = "通知模板不存在: factoryId=" + factoryId + ", templateCode=" + templateCode;
            log.warn("[InAppSender] {}", errMsg);
            // 写一条 FAILED log (recipient 为 null, 表示找不到 template 整体失败)
            writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            return new NotifyResult(NotifyChannel.IN_APP, NotifyStatus.FAILED, errMsg);
        }

        NotifyTemplate template = templateOpt.get();
        String renderedTitle;
        String renderedBody;
        try {
            renderedTitle = templateEngine.render(template.getTitle(), request.params());
            renderedBody = templateEngine.render(template.getBodyTemplate(), request.params());
        } catch (IllegalArgumentException e) {
            String errMsg = "模板渲染失败: " + e.getMessage();
            log.warn("[InAppSender] {}", errMsg);
            // 对每个 recipient 都写一条 FAILED
            if (recipients != null) {
                for (Long uid : recipients) {
                    writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
                }
            } else {
                writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            }
            return new NotifyResult(NotifyChannel.IN_APP, NotifyStatus.FAILED, errMsg);
        }

        // 2. 对每个 recipient 写一条 SENT log
        // 写 audit log 失败 → throw NotifyAuditException, 由 NotifySenderRegistry 捕获标 FAILED.
        // 不再尝试"写一条 FAILED log 替代" — 那条 fallback 也会以同样原因失败, 制造静默 swallow 假象.
        int sentCount = 0;
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                writeLog(factoryId, templateCode, uid, NotifyStatus.SENT, null);
                sentCount++;
                log.debug("[InAppSender] 站内信已发: factoryId={}, recipient={}, title={}",
                        factoryId, uid, renderedTitle);
            }
        }

        log.info("[InAppSender] 站内信发送完成: factoryId={}, templateCode={}, recipientCount={}, sentCount={}",
                factoryId, templateCode, recipients != null ? recipients.size() : 0, sentCount);

        return new NotifyResult(NotifyChannel.IN_APP, NotifyStatus.SENT, null);
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.IN_APP;
    }

    /**
     * 写 audit log. 失败 → throw NotifyAuditException 不静默 swallow.
     *
     * <p><b>Phase 3 review High #2/#3 fix</b>: 原实现 {@code log.error + swallow} 会让 sender
     * 返 SENT 但 0 行 audit 落库, 运维无法察觉. 现改为 throw,
     * 由 {@link com.cretas.aims.service.notify.NotifySenderRegistry#sendAll} 在 channel
     * 边界捕获标该 channel FAILED, 但不阻塞其他 channel.
     */
    private void writeLog(String factoryId, String templateCode, Long recipientUserId,
                          NotifyStatus status, String errorMsg) {
        NotifyLog logEntry = NotifyLog.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .recipientUserId(recipientUserId)
                .channel(NotifyChannel.IN_APP)
                .status(status)
                .errorMsg(errorMsg)
                .sentAt(LocalDateTime.now())
                .build();
        try {
            logRepository.save(logEntry);
        } catch (Exception e) {
            InAppSender.log.error(
                    "[InAppSender] Failed to write NotifyLog: factoryId={}, channel={}, recipient={}",
                    factoryId, NotifyChannel.IN_APP, recipientUserId, e);
            throw new NotifyAuditException(
                    "通知发送审计写入失败 — 请联系运维 (channel=IN_APP, recipient="
                            + recipientUserId + ")", e);
        }
    }
}
