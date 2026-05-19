package com.cretas.aims.service.alerts.impl;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AlertEngineService 骨架实现 — Phase 2 Canvas-Alerts skeleton.
 *
 * <p><b>所有方法 throw {@link UnsupportedOperationException}, 等待 sister chat
 * 完整实施.</b> 本 skeleton PR 仅提供:
 * <ul>
 *   <li>类签名 + Spring {@code @Service} 注册</li>
 *   <li>Repository 注入 (验证 entity / repo 编译通过)</li>
 *   <li>方法签名 + Javadoc 留给 sister chat 实现</li>
 * </ul>
 *
 * <p>Sister chat 实施 checklist (Phase 2 B-1):
 * <ol>
 *   <li>{@link #triggerAlert} — 事件路由 + SpEL 评估 (用 {@code SpelExpressionParser}) +
 *       幂等 dedup (1h window) + 事件创建 + 通知派发</li>
 *   <li>{@link #evaluateScheduled} — {@code @Scheduled} + {@code @SchedulerLock(name="alert-eval-" + factoryId)} +
 *       per-type polling 逻辑</li>
 *   <li>{@link #acknowledge} / {@link #resolve} — 状态机 transition + audit 字段填充</li>
 *   <li>BusinessEvent listener — {@code @EventListener} on {@code PurchaseOrderCreated} 等,
 *       call {@link #triggerAlert}</li>
 *   <li>NotificationService 接入 — IN_APP 必工作, WECHAT/DINGTALK/EMAIL 按现有 adapter</li>
 * </ol>
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Slf4j
@Service
public class AlertEngineServiceImpl implements AlertEngineService {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEventRepository eventRepository;

    @Override
    public List<UUID> triggerAlert(String factoryId, AlertType type,
                                   String businessEntityType, String businessEntityId,
                                   Map<String, Object> context) {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: triggerAlert "
                        + "(factoryId=" + factoryId + ", type=" + type + ")");
    }

    @Override
    public List<UUID> evaluateScheduled(String factoryId) {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: evaluateScheduled "
                        + "(factoryId=" + factoryId + ")");
    }

    @Override
    public Page<AlertEvent> findEvents(String factoryId, AlertEventStatus status, Pageable pageable) {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: findEvents "
                        + "(factoryId=" + factoryId + ", status=" + status + ")");
    }

    @Override
    public AlertEvent acknowledge(UUID eventId, Long userId) {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: acknowledge "
                        + "(eventId=" + eventId + ", userId=" + userId + ")");
    }

    @Override
    public AlertEvent resolve(UUID eventId, Long userId) {
        throw new UnsupportedOperationException(
                "Phase 2 sister chat impl pending: resolve "
                        + "(eventId=" + eventId + ", userId=" + userId + ")");
    }
}
