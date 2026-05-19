package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
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
        int sentCount = 0;
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                try {
                    writeLog(factoryId, templateCode, uid, NotifyStatus.SENT, null);
                    sentCount++;
                    log.debug("[InAppSender] 站内信已发: factoryId={}, recipient={}, title={}",
                            factoryId, uid, renderedTitle);
                } catch (Exception e) {
                    String errMsg = "写入 NotifyLog 失败: " + e.getMessage();
                    log.warn("[InAppSender] {}", errMsg, e);
                    writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
                }
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

    private void writeLog(String factoryId, String templateCode, Long recipientUserId,
                          NotifyStatus status, String errorMsg) {
        try {
            NotifyLog log = NotifyLog.builder()
                    .factoryId(factoryId)
                    .templateCode(templateCode)
                    .recipientUserId(recipientUserId)
                    .channel(NotifyChannel.IN_APP)
                    .status(status)
                    .errorMsg(errorMsg)
                    .sentAt(LocalDateTime.now())
                    .build();
            logRepository.save(log);
        } catch (Exception e) {
            // 防御: 写 log 失败不应抛出, 避免影响主流程
            InAppSender.log.error("[InAppSender] 写 NotifyLog 失败 (吞掉异常避免阻塞): {}", e.getMessage(), e);
        }
    }
}
