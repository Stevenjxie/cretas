package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 食品召回事件 — Sprint 8 P3 Phase A.
 *
 * <p>食品安全法第 63 条强制要求: 食品生产企业发现生产食品不符合食品安全标准, 应当
 * 立即停止生产 + 召回已上市销售食品 + 通知相关生产经营者和消费者 + 记录召回和通知情况.
 *
 * <p>Cretas 召回闭环 (Sprint 8 P3 Skill `food-safety-recall`):
 * 1. 触发: 客户投诉 / 内部发现 / 监管通知 → 创建 RecallEvent (status=INVESTIGATING)
 * 2. 追溯: batch_full_trace 找根源 batch + 影响批次 + 影响客户
 * 3. HACCP audit: haccp_checkpoint_review 查 CCP 历史
 * 4. 合规复查: additive_compliance_check 查添加剂限量
 * 5. 行动闭环 (status 流转): NOTIFYING → FROZEN → REPORTED → COMPLETED
 *    每个 action 由 {@link RecallAction} 记录
 *
 * <p>status 流转规则:
 * INVESTIGATING → NOTIFYING (客户通知中)
 *              → FROZEN (库存已冻结)
 *              → REPORTED (监管已上报)
 *              → COMPLETED (闭环, completed_at 必填)
 *
 * <p>event_code 全局唯一 (跨 factory), 格式 "RECALL-YYYYMMDD-NNN".
 *
 * <p>软删除: 召回事件历史追溯必须保留, 物理删除禁止. 错误创建时软删除即可.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "recall_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_recall_event_code", columnNames = {"event_code"})
        },
        indexes = {
                @Index(name = "idx_recall_events_factory",
                        columnList = "factory_id,trigger_time"),
                @Index(name = "idx_recall_events_status",
                        columnList = "factory_id,status")
        })
@Where(clause = "deleted_at IS NULL")
public class RecallEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 召回事件编号 (e.g. "RECALL-20260801-001"). 全局唯一. */
    @Column(name = "event_code", nullable = false, length = 100, unique = true)
    private String eventCode;

    /** 触发原因: 客户投诉 / 内部发现 / 监管通知. */
    @Column(name = "trigger_reason", nullable = false, columnDefinition = "TEXT")
    private String triggerReason;

    /** 涉事产品类别 (e.g. "卤猪蹄", "酱牛肉"). */
    @Column(name = "affected_product_category", nullable = false, length = 100)
    private String affectedProductCategory;

    /** 触发时间. */
    @Column(name = "trigger_time", nullable = false)
    private LocalDateTime triggerTime;

    /** 触发用户 user_id (创建召回事件的人). */
    @Column(name = "triggered_by_user_id", nullable = false)
    private Long triggeredByUserId;

    /**
     * 召回状态:
     * - INVESTIGATING (调查中, 默认初始状态)
     * - NOTIFYING (客户通知中)
     * - FROZEN (库存已冻结)
     * - REPORTED (监管已上报)
     * - COMPLETED (闭环完成, completed_at 必填)
     */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "INVESTIGATING";

    /** 闭环时间 (status=COMPLETED 时填). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 预估损失金额 (库存价值 + 退货 + 监管罚款 + 品牌损失等). */
    @Column(name = "estimated_loss", precision = 19, scale = 2)
    private BigDecimal estimatedLoss;

    /**
     * AUD-4 P1 Lost Update prevention (Canvas Phase A Food Safety Hub, 2026-05-21).
     *
     * <p>召回事件状态流转 (INVESTIGATING → COMPLETED) 多人协作风险高 — 食品安全主管 +
     * 仓管 + 客户主管同时更新 status / completed_at / estimated_loss 时, JPA 乐观锁
     * 防 Lost Update.
     *
     * <p>Column added via Flyway {@code V20260823_02} with {@code DEFAULT 0 NOT NULL}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
