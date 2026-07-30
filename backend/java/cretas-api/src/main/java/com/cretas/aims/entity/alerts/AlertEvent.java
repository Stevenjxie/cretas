package com.cretas.aims.entity.alerts;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 告警事件 — Phase 2 Canvas-Alerts 触发结果记录.
 *
 * <p>每次 {@link AlertRule} 评估为 true 时创建一条事件 record. 状态机
 * {@link AlertEventStatus#OPEN} → {@code ACKNOWLEDGED} → {@code RESOLVED}.
 *
 * <p>支持的 audit / 业务用途:
 * <ul>
 *   <li>仪表盘 (列 OPEN 事件给运营 dashboard)</li>
 *   <li>历史分析 (规则有效性 / 误报率)</li>
 *   <li>合规审计 (库存 / 质量 trace 责任人)</li>
 * </ul>
 *
 * <p>Maps to table {@code alert_events} (V20260620_02).
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Entity
@Table(name = "alert_events",
       indexes = {
           @Index(name = "idx_alert_events_factory_status_created",
                  columnList = "factory_id, status, created_at"),
           @Index(name = "idx_alert_events_rule",
                  columnList = "rule_id"),
           @Index(name = "idx_alert_events_business_entity",
                  columnList = "factory_id, business_entity_type, business_entity_id")
       })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** 触发规则 id. FK 到 alert_rules.id. */
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    /** 工厂租户 key (反规范化, 加速 dashboard 查询). */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 业务实体类型 — 决定 ack / resolve 时可跳转到哪个详情页.
     *
     * <p>e.g. {@code "MATERIAL_BATCH"}, {@code "PURCHASE_ORDER"},
     * {@code "SALES_ORDER"}, {@code "QUALITY_INSPECTION"}.
     */
    @Column(name = "business_entity_type", length = 50)
    private String businessEntityType;

    /** 业务实体 id (VARCHAR(191) 兼容长 UUID / 复合 id). */
    @Column(name = "business_entity_id", length = 191)
    private String businessEntityId;

    /** 严重度 snapshot (rule.severity 在事件创建时的拷贝, 规则后续变化不追溯). */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10, nullable = false)
    private AlertSeverity severity;

    /**
     * 告警消息 — 人读, 含上下文具体数字.
     *
     * <p>e.g. "冻猪蹄库存 25kg 已低于阈值 30kg"
     * <br>e.g. "采购订单 PO-20260518-0023 金额 ¥58,000 超过限额 ¥50,000"
     */
    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    private String message;

    /** 事件状态机. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AlertEventStatus status;

    /** 确认人 userId (用户点 acknowledge 时填). */
    @Column(name = "acked_by_user_id")
    private Long ackedByUserId;

    /** 确认时间. */
    @Column(name = "acked_at")
    private LocalDateTime ackedAt;

    /** 解决人 userId (问题消解后填). */
    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    /** 解决时间. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onPersistEvent() {
        if (status == null) {
            status = AlertEventStatus.OPEN;
        }
        if (severity == null) {
            severity = AlertSeverity.MID;
        }
    }
}
