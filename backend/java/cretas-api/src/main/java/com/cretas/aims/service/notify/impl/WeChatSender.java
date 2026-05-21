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
import com.cretas.aims.service.notify.WeChatNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 企业微信 sender — Phase 3 Canvas-Notify Step T4 + Phase 3 follow-up impl.
 *
 * <p><b>Status</b>: 真实 WorkApp 推送已落地. {@link WeChatNotifier} 负责 HTTP 调用 +
 * access_token 缓存, 本类负责模板加载 / 渲染 / NotifyLog audit 写入.
 *
 * <p><b>recipient mapping caveat</b>: NotifyRequest 的 {@code recipientUserIds} 是内部
 * Long user id, 企微 API 需 string wechatUserId. 当前实现用 {@code Long.toString()} 作为
 * 占位 — 实际生产部署前 sister chat 必须:
 * <ol>
 *   <li>在 {@code User} entity 加 {@code wechatUserId} 字段 (Phase 3 follow-up issue)</li>
 *   <li>注入 {@code UserRepository}, 把 internal id → wechatUserId 映射</li>
 *   <li>对找不到 wechatUserId 的 recipient 写 FAILED log (不要静默 drop)</li>
 * </ol>
 *
 * <p>Test env (credentials 未配置) 行为: {@link WeChatNotifier#isConfigured} 返 false,
 * {@link WeChatNotifier#send} 抛 {@code WeChatNotConfiguredException}, 本类 catch
 * 后写 FAILED audit log — 跟原 stub 行为兼容, fan-out 不阻塞其他 channel.
 *
 * @since 2026-05-18 (Phase 3 skeleton) → 2026-05-21 (real impl via WeChatNotifier)
 */
@Slf4j
@Component
public class WeChatSender implements NotifySender {

    @Autowired
    private NotifyTemplateRepository templateRepository;

    @Autowired
    private NotifyLogRepository logRepository;

    @Autowired
    private TemplateEngine templateEngine;

    @Autowired
    private WeChatNotifier wechatNotifier;

    @Override
    public NotifyResult send(NotifyRequest request) {
        if (request == null) {
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, "request 为空");
        }

        String factoryId = request.factoryId();
        String templateCode = request.templateCode();
        List<Long> recipients = request.recipientUserIds();

        // 1. 加载 template (factory-scoped)
        Optional<NotifyTemplate> templateOpt =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (templateOpt.isEmpty()) {
            String errMsg = "通知模板不存在: factoryId=" + factoryId + ", templateCode=" + templateCode;
            log.warn("[WeChatSender] {}", errMsg);
            writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
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
            log.warn("[WeChatSender] {}", errMsg);
            if (recipients != null && !recipients.isEmpty()) {
                for (Long uid : recipients) {
                    writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
                }
            } else {
                writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            }
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
        }

        // 3. recipient 为空 → FAILED (WeChat WorkApp 必须指定 touser)
        if (recipients == null || recipients.isEmpty()) {
            String errMsg = "企业微信推送必须指定 recipientUserIds (空收件人列表)";
            log.warn("[WeChatSender] {}", errMsg);
            writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
        }

        // 4. internal user id → 企微 userid 映射 (TEMP: Long.toString)
        // Phase 3 follow-up: 注入 UserRepository, 查 user.wechatUserId 字段.
        List<String> wechatUserIds = recipients.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toList());

        // 5. 构造完整消息 (title + body, 用换行分隔 — 企微 text 消息无独立 title 字段)
        String content = buildContent(renderedTitle, renderedBody);

        // 6. 调用 WeChatNotifier 推送
        try {
            wechatNotifier.send(wechatUserIds, content);
        } catch (WeChatNotifier.WeChatNotConfiguredException e) {
            String errMsg = "企业微信渠道未配置 (Phase 3 follow-up: 设置 WECHAT_CORP_ID / "
                    + "WECHAT_APP_SECRET / WECHAT_AGENT_ID 环境变量)";
            log.warn("[WeChatSender] {}", errMsg);
            for (Long uid : recipients) {
                writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
            }
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
        } catch (IllegalArgumentException e) {
            String errMsg = "企业微信参数错误: " + e.getMessage();
            log.warn("[WeChatSender] {}", errMsg);
            for (Long uid : recipients) {
                writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
            }
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
        } catch (Exception e) {
            // IOException / generic HTTP failure / parse failure / errcode!=0 from WeChat API
            String errMsg = "企业微信发送失败: " + e.getMessage();
            log.error("[WeChatSender] {} (factoryId={}, templateCode={}, recipientCount={})",
                    errMsg, factoryId, templateCode, recipients.size(), e);
            for (Long uid : recipients) {
                writeLog(factoryId, templateCode, uid, NotifyStatus.FAILED, errMsg);
            }
            return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
        }

        // 7. Success — 写 SENT log per recipient
        int sentCount = 0;
        for (Long uid : recipients) {
            writeLog(factoryId, templateCode, uid, NotifyStatus.SENT, null);
            sentCount++;
        }
        log.info("[WeChatSender] 企业微信发送完成: factoryId={}, templateCode={}, sentCount={}",
                factoryId, templateCode, sentCount);
        return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.SENT, null);
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.WECHAT;
    }

    /**
     * Compose final message content from rendered title + body.
     *
     * <p>WeChat WorkApp text 消息无独立 title 字段, title 仅作 message preview prefix.
     * 用换行分隔, title 缺省时不加前缀空行.
     */
    static String buildContent(String renderedTitle, String renderedBody) {
        StringBuilder sb = new StringBuilder();
        if (renderedTitle != null && !renderedTitle.isBlank()) {
            sb.append(renderedTitle);
            if (renderedBody != null && !renderedBody.isBlank()) {
                sb.append("\n\n");
            }
        }
        if (renderedBody != null) {
            sb.append(renderedBody);
        }
        // Defensive: never send empty content (WeChat API rejects empty text.content with errcode 44004)
        if (sb.length() == 0) {
            sb.append("(空消息)");
        }
        return sb.toString();
    }

    /**
     * 写 audit log. 失败 → throw NotifyAuditException 不静默 swallow.
     * Phase 3 review High #2/#3 fix — 见 {@link NotifyAuditException} javadoc.
     */
    private void writeLog(String factoryId, String templateCode, Long recipientUserId,
                          NotifyStatus status, String errMsg) {
        NotifyLog logRow = NotifyLog.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .recipientUserId(recipientUserId)
                .channel(NotifyChannel.WECHAT)
                .status(status)
                .errorMsg(errMsg)
                .sentAt(LocalDateTime.now())
                .build();
        try {
            logRepository.save(logRow);
        } catch (Exception e) {
            log.error(
                    "[WeChatSender] Failed to write NotifyLog: factoryId={}, channel={}, recipient={}",
                    factoryId, NotifyChannel.WECHAT, recipientUserId, e);
            throw new NotifyAuditException(
                    "通知发送审计写入失败 — 请联系运维 (channel=WECHAT, recipient="
                            + recipientUserId + ")", e);
        }
    }
}
