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
 * 企业微信 sender — Phase 3 Canvas-Notify Step T4 (stub pending WeChat SDK).
 *
 * <p><b>Phase 3 follow-up</b>: 实际企业微信推送需后续 sister chat 集成 SDK + 配置:
 * <ol>
 *   <li>在 {@code backend/java/cretas-api/pom.xml} 加 dependency:
 *       {@code com.github.binarywang:weixin-java-cp:4.6.0} (企业号 SDK).</li>
 *   <li>在 {@code .env.prod} 加企业微信凭证:
 *       {@code WECHAT_CORP_ID=xxx}, {@code WECHAT_AGENT_ID=xxx}, {@code WECHAT_SECRET=xxx}
 *       (per .claude/rules/CREDENTIAL-MANAGEMENT.md 加新区块).</li>
 *   <li>本类注入 {@code WxCpService}, 调用 {@code messageService.send()} 发送应用消息.
 *       recipient 解析: 用 user.wechatId 或 user.phone 查企微 userid (需在 User entity 加字段).</li>
 *   <li>或简化方案: 群机器人 webhook (无需 SDK, 直接 POST JSON 到
 *       {@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx}).</li>
 * </ol>
 *
 * <p>当前 stub 行为: send() 返 FAILED + 写 NotifyLog 显式标记 "WeChat channel not yet configured",
 * 避免 fan-out 静默 swallow.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@Component
public class WeChatSender implements NotifySender {

    @Autowired
    private NotifyLogRepository logRepository;

    @Override
    public NotifyResult send(NotifyRequest request) {
        String errMsg = "企业微信渠道暂未配置 (Phase 3 follow-up: 加 weixin-java-cp SDK + WECHAT_CORP_ID 等凭证)";
        log.warn("[WeChatSender] {}, factoryId={}, templateCode={}",
                errMsg, request.factoryId(), request.templateCode());

        List<Long> recipients = request.recipientUserIds();
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                writeLog(request.factoryId(), request.templateCode(), uid, errMsg);
            }
        } else {
            writeLog(request.factoryId(), request.templateCode(), null, errMsg);
        }

        return new NotifyResult(NotifyChannel.WECHAT, NotifyStatus.FAILED, errMsg);
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.WECHAT;
    }

    private void writeLog(String factoryId, String templateCode, Long recipientUserId, String errMsg) {
        try {
            NotifyLog logRow = NotifyLog.builder()
                    .factoryId(factoryId)
                    .templateCode(templateCode)
                    .recipientUserId(recipientUserId)
                    .channel(NotifyChannel.WECHAT)
                    .status(NotifyStatus.FAILED)
                    .errorMsg(errMsg)
                    .sentAt(LocalDateTime.now())
                    .build();
            logRepository.save(logRow);
        } catch (Exception e) {
            log.error("[WeChatSender] 写 NotifyLog 失败: {}", e.getMessage(), e);
        }
    }
}
