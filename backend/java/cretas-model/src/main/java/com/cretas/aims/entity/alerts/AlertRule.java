package com.cretas.aims.entity.alerts;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 告警规则 — Phase 2 Canvas-Alerts 配置实体.
 *
 * <p>per-factory 配置, 由 factory_super_admin / permission_admin 在 Canvas
 * "预警规则" tab 维护. AI Chat 也可通过 {@code alert_rule_create} /
 * {@code alert_rule_update} 等 Tool 自然语言操作.
 *
 * <p>触发机制 (Phase 2 sister chat 实施):
 * <ul>
 *   <li><b>Event-driven</b> — {@code ApplicationEventPublisher} 发布
 *       {@code BusinessEvent}, {@code AlertEventListener} 评估
 *       {@code triggerConditionSpel} (SpEL) 决定是否创建 {@link AlertEvent}.</li>
 *   <li><b>Scheduled</b> — {@code @Scheduled} 定期扫描 (期临 / 应收 / 库存
 *       falling-edge), 用 {@code @SchedulerLock} 防多实例重复.</li>
 * </ul>
 *
 * <p>Maps to table {@code alert_rules} (V20260620_01).
 *
 * @since 2026-05-18 (Phase 2 skeleton)
 */
@Entity
@Table(name = "alert_rules",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_alert_rules_factory_name",
                             columnNames = {"factory_id", "rule_name"})
       },
       indexes = {
           @Index(name = "idx_alert_rules_factory_enabled",
                  columnList = "factory_id, enabled")
       })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** 工厂租户 key. */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** 告警类型 — 决定 trigger 路由到哪套业务上下文检查. */
    @Convert(converter = AlertTypeConverter.class)   // 容错: 未知 alert_type → null 跳过, 不阻断启动 (见 AlertTypeConverter)
    @Column(name = "alert_type", length = 50, nullable = false)
    private AlertType alertType;

    /** 规则名称 — 显示用 (e.g. "冻猪蹄低库存预警"). UNIQUE(factory_id, rule_name). */
    @Column(name = "rule_name", length = 255, nullable = false)
    private String ruleName;

    /**
     * 触发条件 — SpEL 表达式. 评估时绑定 {@code #context.xxx} 作业务上下文.
     *
     * <p>e.g. {@code "#context.amount >= 50000"} (PO_AMOUNT_THRESHOLD)
     * <br>e.g. {@code "#context.stockLevel < #context.minStockLevel"} (INVENTORY_LOW)
     *
     * <p>NULL 时表示规则只靠 type 内置默认逻辑 (e.g. INVENTORY_EXPIRING 默认
     * {@code expiryDate <= today + 7d}, 无需 SpEL).
     */
    @Column(name = "trigger_condition_spel", columnDefinition = "TEXT")
    private String triggerConditionSpel;

    /** 严重度 snapshot — 影响通知优先级. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10, nullable = false)
    private AlertSeverity severity;

    /** 启用状态 — false 时 scheduler / listener 跳过此规则. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /**
     * 通知渠道 — JSONB list&lt;String&gt;.
     *
     * <p>合法值: {@code "WECHAT"} / {@code "DINGTALK"} / {@code "EMAIL"} /
     * {@code "IN_APP"}. Phase 2 内 IN_APP 必工作, 其他渠道依赖现有
     * {@code NotificationService} adapter.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "notify_channels", columnDefinition = "jsonb")
    private List<String> notifyChannels;

    /**
     * 通知接收角色 — JSONB list&lt;String&gt;.
     *
     * <p>e.g. {@code ["procurement_manager", "warehouse_admin"]}. Service 层
     * 解析 factoryId + role list → 具体 userId 集合 → 推送.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "notify_roles", columnDefinition = "jsonb")
    private List<String> notifyRoles;

    /**
     * AUD-4 P1 (edge audit 2026-05-20): JPA optimistic locking version.
     *
     * <p>Hibernate manages this column automatically — first save inserts
     * with {@code version=0}, every subsequent {@code save()} or
     * {@code merge()} increments it. If two parallel PUT requests load
     * the same row (both see {@code version=N}), Hibernate's flush detects
     * the stale snapshot on the loser and throws
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
     * {@link com.cretas.aims.exception.GlobalExceptionHandler} catches it
     * and surfaces a 409 with the actionHint
     * "请刷新页面查看最新数据后再编辑" — the user can retry without
     * silently losing the other user's edits (Lost Update prevention).
     *
     * <p>Column is added via Flyway {@code V20260626_02} with
     * {@code DEFAULT 0 NOT NULL}; backfill is implicit because the migration
     * runs before any code reads/writes the column.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onPersistRule() {
        if (enabled == null) {
            enabled = true;
        }
        if (severity == null) {
            severity = AlertSeverity.MID;
        }
        if (notifyChannels == null) {
            notifyChannels = new ArrayList<>();
        }
        if (notifyRoles == null) {
            notifyRoles = new ArrayList<>();
        }
    }
}
