package com.cretas.aims.entity.ml;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.ProcessingStageType;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 工人分配反馈记录实体
 * 记录每次工人分配的结果，用于LinUCB模型训练
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Entity
@Table(name = "worker_allocation_feedbacks", indexes = {
        @Index(name = "idx_waf_factory_task", columnList = "factory_id, task_id"),
        @Index(name = "idx_waf_factory_worker", columnList = "factory_id, worker_id"),
        @Index(name = "idx_waf_completed", columnList = "completed_at"),
        @Index(name = "idx_waf_factory_stage", columnList = "factory_id, stage_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class WorkerAllocationFeedback extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 任务ID (生产批次ID或计划ID)
     */
    @Column(name = "task_id", length = 50, nullable = false)
    private String taskId;

    /**
     * 任务类型
     */
    @Column(name = "task_type", length = 30)
    private String taskType;

    /**
     * 工人ID
     */
    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /**
     * 工人工号
     */
    @Column(name = "worker_code", length = 10)
    private String workerCode;

    /**
     * 分配时的上下文特征 (JSON数组)
     */
    @Column(name = "context_features", columnDefinition = "JSON", nullable = false)
    private String contextFeatures;

    /**
     * 任务特征 (JSON)
     */
    @Column(name = "task_features", columnDefinition = "JSON")
    private String taskFeatures;

    /**
     * 工人特征 (JSON)
     */
    @Column(name = "worker_features", columnDefinition = "JSON")
    private String workerFeatures;

    /**
     * 预测分数 (UCB值)
     */
    @Column(name = "predicted_score", precision = 5, scale = 4)
    private BigDecimal predictedScore;

    /**
     * 实际效率 (0-1)
     * 计算方式: 实际产量 / 预期产量
     */
    @Column(name = "actual_efficiency", precision = 5, scale = 4)
    private BigDecimal actualEfficiency;

    /**
     * 实际质量分 (0-1)
     */
    @Column(name = "actual_quality", precision = 5, scale = 4)
    private BigDecimal actualQuality;

    /**
     * 综合奖励值 (0-1)
     * 计算方式: 效率 * 0.6 + 质量 * 0.4
     */
    @Column(name = "reward", precision = 5, scale = 4)
    private BigDecimal reward;

    /**
     * 计划产量
     */
    @Column(name = "planned_quantity", precision = 10, scale = 2)
    private BigDecimal plannedQuantity;

    /**
     * 实际产量
     */
    @Column(name = "actual_quantity", precision = 10, scale = 2)
    private BigDecimal actualQuantity;

    /**
     * 计划工时 (小时)
     */
    @Column(name = "planned_hours", precision = 5, scale = 2)
    private BigDecimal plannedHours;

    /**
     * 实际工时 (小时)
     */
    @Column(name = "actual_hours", precision = 5, scale = 2)
    private BigDecimal actualHours;

    /**
     * 是否超时完成
     */
    @Column(name = "is_overtime")
    private Boolean isOvertime;

    /**
     * 分配时间
     */
    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 是否已用于模型更新
     */
    @Column(name = "is_processed")
    @Builder.Default
    private Boolean isProcessed = false;

    /**
     * 处理时间
     */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    // ==================== Phase 3: 工艺维度增强 ====================

    /**
     * 工艺类型
     * 用于区分不同工艺的效率表现，支持个人效率分解计算
     */
    @Column(name = "stage_type", length = 30)
    @Enumerated(EnumType.STRING)
    private ProcessingStageType stageType;

    /**
     * 团队成员ID列表 (JSON数组)
     * 记录同一任务的所有参与工人，用于个人效率分解
     * 格式: [1, 2, 3, 4, 5]
     */
    @Column(name = "team_composition", columnDefinition = "JSON")
    private String teamComposition;

    // ==================== 辅助方法 ====================

    /**
     * 计算奖励值
     * 综合考虑效率和质量
     */
    public BigDecimal calculateReward() {
        BigDecimal efficiency = this.actualEfficiency != null ? this.actualEfficiency : BigDecimal.ZERO;
        BigDecimal quality = this.actualQuality != null ? this.actualQuality : BigDecimal.ONE;

        // reward = efficiency * 0.6 + quality * 0.4
        this.reward = efficiency.multiply(new BigDecimal("0.6"))
                .add(quality.multiply(new BigDecimal("0.4")));

        return this.reward;
    }

    /**
     * 标记为已处理
     */
    public void markAsProcessed() {
        this.isProcessed = true;
        this.processedAt = LocalDateTime.now();
    }

    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return this.completedAt != null;
    }
}
