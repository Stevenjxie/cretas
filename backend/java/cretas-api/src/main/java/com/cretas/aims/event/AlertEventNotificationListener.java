package com.cretas.aims.event;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.notification.impl.DbNotificationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 告警事件 → 通知派发 (Canvas-Alerts 通用) — 2026-07-11 (餐饮经营体检预警推送).
 *
 * <p>补齐 Phase 2 Canvas-Alerts skeleton 遗留的通知派发缺口: {@link AlertRule} 早已有
 * {@code notifyRoles} / {@code notifyChannels} 字段, 但从未有代码消费它们真正推送
 * (grep 全仓库确认 0 caller). 本 listener 是通用桥接层, 对<b>任何</b> {@code AlertType}
 * 的新事件都生效, 不仅限于餐饮.
 *
 * <p>渠道策略 (per goal: SMS 只用于 HIGH severity, 避免疲劳/误报训练用户忽略):
 * <ul>
 *   <li>{@code severity != HIGH} 且渠道含 IN_APP (或未配置渠道时默认 IN_APP) —
 *       直接调 {@link DbNotificationServiceImpl#notifyRole} (具体类, 绕过
 *       {@code @Primary} bean 选择), 保证<b>只</b>站内通知落库, 不管全局
 *       {@code notification.gateway} 配置如何都不会意外发短信.</li>
 *   <li>{@code severity == HIGH} 且渠道含 SMS — 调注入的 {@link NotificationService}
 *       接口 bean (视 {@code notification.gateway} 配置, 可能是
 *       {@code AliyunSmsNotificationServiceImpl} 或退化到
 *       {@code DbNotificationServiceImpl}). 已有的 graceful-degrade 设计保证:
 *       站内通知总是落库, 短信仅在网关真配置时才发, 不会伪造"已发送".</li>
 * </ul>
 *
 * <p>诚实审计 (禁降级原则): 无论短信网关是否配置, 都在 {@code notify_logs}
 * (已有 Phase 3 Canvas-Notify 表, 复用不新建 schema) 写一条 SMS channel 记录 —
 * 网关未配置时 status=FAILED + errorMsg 明确说明"短信网关未配置", 让运维/老板从
 * 现有 NotifyLog 审计工具就能看到"为什么没收到短信", 而不是静默无声.
 *
 * @since 2026-07-11
 */
@Slf4j
@Component
public class AlertEventNotificationListener {

    private static final String TEMPLATE_CODE = "RESTAURANT_HEALTH_ALERT";

    @Autowired
    private AlertEventRepository eventRepository;

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private NotifyLogRepository notifyLogRepository;

    /** Interface bean — @Primary 选中的实现 (logging / DB / Aliyun SMS, 视配置). */
    @Autowired
    private NotificationService notificationService;

    /** 具体类 — 保证 in-app-only 路径绕过全局 SMS 网关配置, 不受其影响. */
    @Autowired
    private DbNotificationServiceImpl dbNotificationServiceImpl;

    /**
     * AFTER_COMMIT (not plain @EventListener): {@code triggerAlert} publishes this
     * event from inside its own @Transactional method. A plain @Async @EventListener
     * would race the outer transaction's commit — the background thread could
     * {@code findById} before the INSERT is visible on another connection, silently
     * dropping the notification (empty Optional → early return, no error, no retry).
     * AFTER_COMMIT guarantees the AlertEvent + AlertRule are durably visible before
     * this listener's own (new) transaction/connection queries them. @Async keeps
     * the notification work off the caller's thread.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handle(AlertEventCreatedEvent evt) {
        if (evt == null || evt.alertEventId() == null) {
            return;
        }
        AlertEvent event = eventRepository.findById(evt.alertEventId()).orElse(null);
        if (event == null) {
            log.warn("[AlertEventNotificationListener] AlertEvent 不存在(可能已删除): id={}", evt.alertEventId());
            return;
        }
        AlertRule rule = ruleRepository.findById(event.getRuleId()).orElse(null);
        if (rule == null) {
            log.warn("[AlertEventNotificationListener] AlertRule 不存在: ruleId={}, eventId={}",
                    event.getRuleId(), event.getId());
            return;
        }

        List<String> notifyRoles = rule.getNotifyRoles();
        if (notifyRoles == null || notifyRoles.isEmpty()) {
            log.debug("[AlertEventNotificationListener] rule={} 未配置 notifyRoles, 跳过推送", rule.getId());
            return;
        }

        List<String> channelsRaw = rule.getNotifyChannels();
        boolean wantsInApp = channelsRaw == null || channelsRaw.isEmpty()
                || channelsRaw.stream().anyMatch(c -> "IN_APP".equalsIgnoreCase(c));
        boolean wantsSms = channelsRaw != null && channelsRaw.stream().anyMatch(c -> "SMS".equalsIgnoreCase(c));

        boolean isHigh = event.getSeverity() == AlertSeverity.HIGH;
        String title = String.format("[%s] %s", severityLabel(event.getSeverity()), ruleTitle(rule));
        String body = event.getMessage();

        int recipientRoleCount = 0;
        for (String roleCode : notifyRoles) {
            if (roleCode == null || roleCode.isBlank()) {
                continue;
            }
            recipientRoleCount++;
            try {
                if (isHigh && wantsSms) {
                    // DB-persist (in-app) + SMS-if-configured (graceful degrade
                    // already built into AliyunSmsNotificationServiceImpl —
                    // unconfigured → warns + skips SMS, still persists in-app).
                    notificationService.notifyRole(event.getFactoryId(), roleCode, title, body);
                    recordSmsAudit(event, roleCode);
                } else if (wantsInApp) {
                    // Guaranteed in-app-only — bypasses whatever the global
                    // notification.gateway is set to, so MID/LOW severity never
                    // triggers SMS regardless of prod config (SMS fatigue guard).
                    dbNotificationServiceImpl.notifyRole(event.getFactoryId(), roleCode, title, body);
                }
            } catch (Exception e) {
                log.error("[AlertEventNotificationListener] 推送失败: eventId={}, role={}",
                        event.getId(), roleCode, e);
            }
        }

        log.info("[AlertEventNotificationListener] eventId={} severity={} roles={} inApp={} sms={} 推送完成",
                event.getId(), event.getSeverity(), recipientRoleCount, wantsInApp, isHigh && wantsSms);
    }

    /**
     * 诚实审计: 记一条 NotifyLog (channel=SMS). 网关未配置时 status=FAILED +
     * 明确 errorMsg, 不伪造"已发送". recipientUserId 留 null (角色广播, 非单用户).
     */
    private void recordSmsAudit(AlertEvent event, String roleCode) {
        boolean smsAvailable = notificationService.isSmsAvailable();
        NotifyLog logRow = NotifyLog.builder()
                .factoryId(event.getFactoryId())
                .templateCode(TEMPLATE_CODE)
                .recipientUserId(null)
                .channel(NotifyChannel.SMS)
                .status(smsAvailable ? NotifyStatus.SENT : NotifyStatus.FAILED)
                .errorMsg(smsAvailable ? null
                        : "短信网关未配置 (notification.gateway != aliyun-sms 或签名/模板未审批) — "
                                + "已推送站内通知, 角色=" + roleCode + ", 事件=" + event.getId())
                .sentAt(LocalDateTime.now())
                .build();
        try {
            notifyLogRepository.save(logRow);
        } catch (Exception e) {
            // Audit write failure must not block the notification path that already ran.
            log.error("[AlertEventNotificationListener] NotifyLog(SMS) 写入失败: eventId={}, role={}",
                    event.getId(), roleCode, e);
        }
    }

    private static String severityLabel(AlertSeverity severity) {
        if (severity == null) return "提醒";
        return switch (severity) {
            case HIGH -> "严重";
            case MID -> "警告";
            case LOW -> "提示";
        };
    }

    private static String ruleTitle(AlertRule rule) {
        return Optional.ofNullable(rule.getRuleName()).filter(s -> !s.isBlank()).orElse("经营预警");
    }
}
