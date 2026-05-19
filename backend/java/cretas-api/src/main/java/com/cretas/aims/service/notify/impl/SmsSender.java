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
 * 短信 sender — Phase 3 Canvas-Notify Step T4 (stub pending Aliyun SMS SDK).
 *
 * <p><b>Phase 3 follow-up</b>: 实际短信发送需后续 sister chat 集成 Aliyun SMS SDK:
 * <ol>
 *   <li>在 {@code backend/java/cretas-api/pom.xml} 加 dependency:
 *       {@code com.aliyun:dysmsapi20170525:2.0.24} 或 {@code com.aliyun:aliyun-java-sdk-dysmsapi}.</li>
 *   <li>配置凭证: {@code ALIBABA_ACCESSKEY_ID} / {@code ALIBABA_SECRET_KEY} 已在 .env.prod
 *       (per CREDENTIAL-MANAGEMENT.md 已记录). 新增: {@code SMS_SIGN_NAME=Cretas},
 *       {@code SMS_TEMPLATE_CODE=SMS_xxx} (per-业务短信模板需在阿里云后台预审).</li>
 *   <li>本类注入 SDK Client, 调用 {@code sendSms()}. recipient 解析: user.phone 字段
 *       (User entity 已有). 注意: 阿里云短信模板变量与 NotifyTemplate 变量需对齐.</li>
 *   <li><b>合规提醒</b>: 短信营销需备案签名 + 模板, 业务通知短信请走"通知"类模板,
 *       不要发营销内容 (违反工信部规定).</li>
 * </ol>
 *
 * <p>当前 stub 行为: send() 返 FAILED + 写 NotifyLog 标记 "SMS channel not yet configured".
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class SmsSender implements NotifySender {

    @Autowired
    private NotifyLogRepository logRepository;

    @Override
    public NotifyResult send(NotifyRequest request) {
        String errMsg = "短信渠道暂未配置 (Phase 3 follow-up: 集成 aliyun dysmsapi SDK + SMS_SIGN_NAME/SMS_TEMPLATE_CODE 配置)";
        log.warn("[SmsSender] {}, factoryId={}, templateCode={}",
                errMsg, request.factoryId(), request.templateCode());

        List<Long> recipients = request.recipientUserIds();
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                writeLog(request.factoryId(), request.templateCode(), uid, errMsg);
            }
        } else {
            writeLog(request.factoryId(), request.templateCode(), null, errMsg);
        }

        return new NotifyResult(NotifyChannel.SMS, NotifyStatus.FAILED, errMsg);
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.SMS;
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
                .channel(NotifyChannel.SMS)
                .status(NotifyStatus.FAILED)
                .errorMsg(errMsg)
                .sentAt(LocalDateTime.now())
                .build();
        try {
            logRepository.save(logRow);
        } catch (Exception e) {
            log.error(
                    "[SmsSender] Failed to write NotifyLog: factoryId={}, channel={}, recipient={}",
                    factoryId, NotifyChannel.SMS, recipientUserId, e);
            throw new NotifyAuditException(
                    "通知发送审计写入失败 — 请联系运维 (channel=SMS, recipient="
                            + recipientUserId + ")", e);
        }
    }
}
