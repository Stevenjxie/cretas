package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.service.notify.NotifyAuditException;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 钉钉 sender — Phase 3 Canvas-Notify Step T4 (stub pending DingTalk integration).
 *
 * <p><b>Phase 3 follow-up</b>: 实际钉钉推送有两种方式:
 * <ol>
 *   <li><b>群机器人 webhook (简单)</b>: 直接 POST JSON 到
 *       {@code https://oapi.dingtalk.com/robot/send?access_token=xxx}.
 *       仅需 webhook URL + secret, 不需 SDK. 推荐用 Spring {@code RestTemplate} 或 {@code WebClient}.</li>
 *   <li><b>工作通知 (复杂)</b>: oapi.dingtalk.com/message/corpconversation/asyncsend_v2,
 *       需企业开发者权限 + agentId, 可定向到具体员工.</li>
 * </ol>
 *
 * <p>配置存储: webhook URL + secret 存在 {@code factory_settings} 表 (按 factoryId 隔离),
 * 或加入 NotifyTemplate.variables_schema_json (per-template 配置).
 *
 * <p>当前 stub 行为: send() 返 FAILED + 写 NotifyLog 标记 "DingTalk channel not yet configured".
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class DingTalkSender implements NotifySender {

    @Autowired
    private NotifyLogRepository logRepository;

    @Override
    public NotifyResult send(NotifyRequest request) {
        String errMsg = "钉钉渠道暂未配置 (Phase 3 follow-up: 接入钉钉群机器人 webhook 或 oapi 工作通知)";
        log.warn("[DingTalkSender] {}, factoryId={}, templateCode={}",
                errMsg, request.factoryId(), request.templateCode());

        List<Long> recipients = request.recipientUserIds();
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                writeLog(request.factoryId(), request.templateCode(), uid, errMsg);
            }
        } else {
            writeLog(request.factoryId(), request.templateCode(), null, errMsg);
        }

        return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.DINGTALK;
    }

    /**
     * 写 audit log. 失败 → throw NotifyAuditException 不静默 swallow.
     * Phase 3 review High #2/#3 fix — 见 {@link NotifyAuditException} javadoc.
     */
    private void writeLog(String factoryId, String templateCode, Long recipientUserId, String errMsg) {
        NotifyLog logRow = NotifyLog.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .recipientUserId(recipientUserId)
                .channel(NotifyChannel.DINGTALK)
                .status(NotifyStatus.FAILED)
                .errorMsg(errMsg)
                .sentAt(LocalDateTime.now())
                .build();
        try {
            logRepository.save(logRow);
        } catch (Exception e) {
            log.error(
                    "[DingTalkSender] Failed to write NotifyLog: factoryId={}, channel={}, recipient={}",
                    factoryId, NotifyChannel.DINGTALK, recipientUserId, e);
            throw new NotifyAuditException(
                    "通知发送审计写入失败 — 请联系运维 (channel=DINGTALK, recipient="
                            + recipientUserId + ")", e);
        }
    }
}
