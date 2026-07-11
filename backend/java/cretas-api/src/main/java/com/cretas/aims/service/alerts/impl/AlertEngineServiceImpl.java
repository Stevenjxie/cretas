package com.cretas.aims.service.alerts.impl;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.AlertEventCreatedEvent;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AlertEngineService 实现 — Phase 2 Canvas-Alerts.
 *
 * <p>核心职责:
 * <ul>
 *   <li>事件路由 — listener 接 BusinessEvent → {@link #triggerAlert} → 匹配 enabled rules
 *       → SpEL 评估 → 创建 AlertEvent</li>
 *   <li>定时扫描 — {@link #evaluateScheduled} 由 {@code AlertScheduledEvaluator}
 *       @Scheduled 触发 (per-factory polling)</li>
 *   <li>幂等 — 同 (rule_id, business_entity_id) 1 小时窗口去重</li>
 *   <li>ack / resolve — 状态机推进 + 责任人记录</li>
 * </ul>
 *
 * <p>Sister chat (Phase 2 B-2/B-3): 通知派发 (WECHAT/DINGTALK/EMAIL) + dashboard UI 接入.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Service
public class AlertEngineServiceImpl implements AlertEngineService {

    /** 幂等 dedup 窗口 — 同 (ruleId, entityId) 在 1 小时内不重复创建事件. */
    private static final long DEDUP_WINDOW_MINUTES = 60L;

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEventRepository eventRepository;

    @Autowired
    private SandboxedSpelEvaluator spelEvaluator;

    /**
     * 通知派发解耦 — {@link #triggerAlert} 落库成功后发布此事件,
     * {@code AlertEventNotificationListener} (@Async) 消费并按
     * {@code rule.notifyRoles} / {@code rule.notifyChannels} 推送
     * 站内通知 + (HIGH severity) 短信. 见 event/AlertEventNotificationListener.java.
     *
     * @since 2026-07-11 (餐饮经营体检预警推送 — 补齐 Phase 2 skeleton 遗留的
     *        "Sister chat (Phase 2 B-2/B-3): 通知派发" 缺口, 通用于所有 AlertType)
     */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public List<UUID> triggerAlert(String factoryId, AlertType type,
                                   String businessEntityType, String businessEntityId,
                                   Map<String, Object> context) {
        if (factoryId == null || type == null) {
            log.warn("triggerAlert: missing required args factoryId={}, type={}", factoryId, type);
            return Collections.emptyList();
        }

        log.debug("triggerAlert: factoryId={}, type={}, entityType={}, entityId={}",
                factoryId, type, businessEntityType, businessEntityId);

        List<AlertRule> rules = ruleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(factoryId, type);
        if (rules.isEmpty()) {
            log.debug("triggerAlert: no enabled rules for factoryId={}, type={}", factoryId, type);
            return Collections.emptyList();
        }

        List<UUID> createdEventIds = new ArrayList<>();
        for (AlertRule rule : rules) {
            try {
                if (!evaluateRule(rule, context)) {
                    log.debug("triggerAlert: rule {} not matched (SpEL false)", rule.getId());
                    continue;
                }

                // Dedup check: skip if a recent OPEN/ACK event exists for same (rule, entity).
                if (businessEntityId != null && isDuplicate(rule.getId(), businessEntityId)) {
                    log.debug("triggerAlert: rule {} dedup-skipped for entity {}",
                            rule.getId(), businessEntityId);
                    continue;
                }

                AlertEvent event = buildEvent(rule, factoryId, businessEntityType, businessEntityId, context);
                AlertEvent saved = eventRepository.save(event);
                createdEventIds.add(saved.getId());

                log.info("AlertEvent created: id={}, factoryId={}, type={}, ruleId={}, severity={}",
                        saved.getId(), factoryId, type, rule.getId(), saved.getSeverity());

                // Notify dispatch is decoupled via event — publish AFTER save so the
                // listener (which re-loads the entity in its own @Async thread) never
                // races the transaction. See eventPublisher javadoc above.
                try {
                    eventPublisher.publishEvent(new AlertEventCreatedEvent(saved.getId()));
                } catch (Exception e) {
                    // Notify dispatch failure must never roll back / block alert
                    // persistence — the standing alert itself is already durable.
                    log.error("triggerAlert: publish AlertEventCreatedEvent failed for eventId={}",
                            saved.getId(), e);
                }
            } catch (SpelEvaluationFailure e) {
                log.warn("triggerAlert: rule {} SpEL eval failed — {} (hint: {})",
                        rule.getId(), e.getUserMessage(), e.getActionHint());
            } catch (Exception e) {
                log.error("triggerAlert: rule {} unexpected failure", rule.getId(), e);
            }
        }

        return createdEventIds;
    }

    @Override
    @Transactional
    public List<UUID> evaluateScheduled(String factoryId) {
        if (factoryId == null) {
            log.warn("evaluateScheduled: factoryId required");
            return Collections.emptyList();
        }

        log.debug("evaluateScheduled: factoryId={}", factoryId);
        // Scheduled scans are dispatched by per-type scheduled evaluators
        // (AlertInventoryExpiringScheduler / AlertCustomerPaymentOverdueScheduler /
        // AlertSupplierPayableDueScheduler / AlertSalesDeclineScheduler). They each
        // call back to triggerAlert(...) with synthesized context per business entity.
        // This method is a no-op aggregator for manual evaluation requests.
        List<AlertRule> rules = ruleRepository.findByFactoryIdAndEnabledTrue(factoryId);
        log.debug("evaluateScheduled: {} enabled rules for factoryId={}", rules.size(), factoryId);
        return Collections.emptyList();
    }

    @Override
    public Page<AlertEvent> findEvents(String factoryId, AlertEventStatus status, Pageable pageable) {
        if (factoryId == null) {
            throw new IllegalArgumentException("factoryId required");
        }
        if (status != null) {
            return eventRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageable);
        }
        return eventRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
    }

    @Override
    @Transactional
    public AlertEvent acknowledge(UUID eventId, Long userId) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId required");
        }
        AlertEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AlertEvent not found: id=" + eventId));

        if (event.getStatus() != AlertEventStatus.OPEN) {
            throw new IllegalStateException(
                    "Cannot acknowledge — event status is " + event.getStatus()
                            + " (only OPEN is acknowledgeable). Event id: " + eventId);
        }

        event.setStatus(AlertEventStatus.ACKNOWLEDGED);
        event.setAckedByUserId(userId);
        event.setAckedAt(LocalDateTime.now());
        AlertEvent saved = eventRepository.save(event);

        log.info("AlertEvent acknowledged: id={}, ackedBy={}", eventId, userId);
        return saved;
    }

    @Override
    @Transactional
    public AlertEvent resolve(UUID eventId, Long userId) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId required");
        }
        AlertEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AlertEvent not found: id=" + eventId));

        if (event.getStatus() == AlertEventStatus.RESOLVED) {
            log.debug("AlertEvent already resolved: id={}, noop", eventId);
            return event;
        }

        event.setStatus(AlertEventStatus.RESOLVED);
        event.setResolvedByUserId(userId);
        event.setResolvedAt(LocalDateTime.now());
        AlertEvent saved = eventRepository.save(event);

        log.info("AlertEvent resolved: id={}, resolvedBy={}", eventId, userId);
        return saved;
    }

    // ==================== Internal helpers ====================

    /**
     * Evaluate a rule's SpEL trigger condition against the given business context.
     *
     * <p>If the rule has no SpEL (e.g. INVENTORY_EXPIRING uses scheduler default
     * logic), returns {@code true} — the caller decides whether to create an
     * event based on its own check.
     *
     * @return {@code true} when SpEL is null/empty (use default logic), or when
     *         SpEL evaluates to boolean {@code true}.
     */
    private boolean evaluateRule(AlertRule rule, Map<String, Object> context) {
        String spel = rule.getTriggerConditionSpel();
        if (spel == null || spel.trim().isEmpty()) {
            // No SpEL = use type's built-in logic (caller already decided to fire).
            return true;
        }
        Map<String, Object> vars = context != null ? context : Collections.emptyMap();
        // SpEL binds business context as #context — value is a Map<String, Object>.
        // SandboxedSpelEvaluator's SimpleEvaluationContext.forReadOnlyDataBinding()
        // resolves #context['key'] via map indexing. Rules should use this form
        // (or wrap a typed DTO with bean getters).
        //
        // Convenience: also expose each top-level context key as a #-prefixed
        // variable so simple rules like "#amount >= 50000" or "#currentStock < #minStockLevel"
        // also work without the #context prefix.
        Map<String, Object> spelVars = new java.util.HashMap<>(vars);
        spelVars.put("context", vars);
        return spelEvaluator.evaluateBoolean(spel, spelVars);
    }

    /**
     * Check whether a recent (within dedup window) OPEN or ACKNOWLEDGED event
     * exists for the same (rule, business entity).
     */
    private boolean isDuplicate(UUID ruleId, String businessEntityId) {
        if (ruleId == null || businessEntityId == null) {
            return false;
        }
        LocalDateTime since = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
        List<AlertEvent> recent = eventRepository
                .findByRuleIdAndBusinessEntityIdAndStatusInAndCreatedAtAfter(
                        ruleId, businessEntityId,
                        List.of(AlertEventStatus.OPEN, AlertEventStatus.ACKNOWLEDGED),
                        since);
        return !recent.isEmpty();
    }

    /**
     * Build an AlertEvent for persistence. Message defaults to "context" string;
     * listeners can pre-format and override via {@code context.get("message")}.
     */
    private AlertEvent buildEvent(AlertRule rule, String factoryId, String businessEntityType,
                                  String businessEntityId, Map<String, Object> context) {
        Object preformatted = context != null ? context.get("message") : null;
        String message;
        if (preformatted instanceof String s && !s.isBlank()) {
            message = s;
        } else {
            message = String.format("[%s] %s 触发: %s",
                    rule.getAlertType(),
                    rule.getRuleName(),
                    truncateContext(context));
        }

        return AlertEvent.builder()
                .ruleId(rule.getId())
                .factoryId(factoryId)
                .businessEntityType(businessEntityType)
                .businessEntityId(businessEntityId)
                .severity(resolveSeverity(rule, context))
                .message(message)
                .status(AlertEventStatus.OPEN)
                .build();
    }

    /**
     * Per-instance severity override — 2026-07-11 (餐饮经营体检预警推送).
     *
     * <p>Original design snapshotted {@code rule.getSeverity()} onto every event
     * (1 severity per rule). Restaurant health-check diagnoses vary in severity
     * per metric per sweep (critical vs warning) under a SINGLE rule — forcing a
     * separate rule per severity would fragment notifyRoles/notifyChannels config
     * for no reason. {@code context.get("severity")} lets a caller pass a real
     * per-event {@link AlertSeverity} while every existing caller (which never
     * sets this key) keeps today's rule-snapshot behavior unchanged.
     *
     * @return context override if present and valid, else {@code rule.getSeverity()}
     */
    private AlertSeverity resolveSeverity(AlertRule rule, Map<String, Object> context) {
        if (context != null && context.get("severity") instanceof AlertSeverity s) {
            return s;
        }
        return rule.getSeverity();
    }

    private String truncateContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "无上下文";
        }
        String s = context.toString();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
