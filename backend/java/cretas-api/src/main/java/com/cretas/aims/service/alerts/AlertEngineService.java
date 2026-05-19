package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 告警引擎服务 — Phase 2 Canvas-Alerts skeleton 接口.
 *
 * <p><b>本 Skeleton PR 不实现具体逻辑.</b> Sister chat 在 Phase 2 B-1
 * 实施 (event listener + scheduler + SpEL 评估 + 通知派发).
 *
 * <p>核心职责 (sister chat 实施时):
 * <ul>
 *   <li>事件路由 — 接收 {@code BusinessEvent}, 匹配启用规则, 评估 SpEL</li>
 *   <li>定时扫描 — 每 15 分钟 (per-factory 可配) 扫描需要 polling 的 type
 *       (INVENTORY_EXPIRING / CUSTOMER_PAYMENT_OVERDUE / SALES_DECLINE)</li>
 *   <li>事件创建 — 评估为 true 时持久化 {@link AlertEvent} + 触发通知派发</li>
 *   <li>ack / resolve — 状态机推进, 记录责任人 / 时间戳</li>
 *   <li>幂等 — 同 (rule_id, business_entity_id) 1 小时内不重复 (dedup window)</li>
 * </ul>
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
public interface AlertEngineService {

    /**
     * 触发告警评估 — 业务 Service 在写操作后调用.
     *
     * <p>e.g. {@code PurchaseService.createPurchaseOrder} 完成后调用
     * {@code triggerAlert(factoryId, PO_AMOUNT_THRESHOLD, "PURCHASE_ORDER", poId, Map.of("amount", 50000))}.
     *
     * @param factoryId 工厂 ID
     * @param type 告警类型
     * @param businessEntityType 业务实体类型 (e.g. "PURCHASE_ORDER")
     * @param businessEntityId 业务实体 ID
     * @param context SpEL 评估时绑定为 {@code #context.xxx} 的业务上下文
     * @return 创建的事件 ids (可能 0..N, 取决于多少 rule 匹配)
     */
    List<UUID> triggerAlert(String factoryId, AlertType type,
                            String businessEntityType, String businessEntityId,
                            Map<String, Object> context);

    /**
     * 定时扫描评估 — {@code @Scheduled} 触发, 一次扫一个 factory 的全部启用规则.
     *
     * <p>主要服务需要 polling 的 type (期临 / 应收 / 销售下滑).
     *
     * @param factoryId 工厂 ID
     * @return 本次扫描创建的事件 ids
     */
    List<UUID> evaluateScheduled(String factoryId);

    /**
     * 查询工厂的告警事件.
     *
     * @param factoryId 工厂 ID
     * @param status 事件状态过滤 (null = 全部)
     * @param pageable 分页 / 排序
     * @return 分页结果
     */
    Page<AlertEvent> findEvents(String factoryId, AlertEventStatus status, Pageable pageable);

    /**
     * 确认事件 — 用户在 UI 点 "ack" 时调用.
     *
     * @param eventId 事件 ID
     * @param userId 确认人
     * @return 更新后的事件
     */
    AlertEvent acknowledge(UUID eventId, Long userId);

    /**
     * 解决事件 — 用户在 UI 点 "resolve" / 系统自动 clear 时调用.
     *
     * @param eventId 事件 ID
     * @param userId 解决人 (系统自动 clear 时传 null)
     * @return 更新后的事件
     */
    AlertEvent resolve(UUID eventId, Long userId);
}
