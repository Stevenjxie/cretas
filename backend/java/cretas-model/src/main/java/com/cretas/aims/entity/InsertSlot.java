package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 紧急插单时段实体
 *
 * 用于管理可用于紧急插单的时间窗口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "insert_slots",
       indexes = {
           @Index(name = "idx_insert_slots_factory_time", columnList = "factory_id, start_time, end_time"),
           @Index(name = "idx_insert_slots_status", columnList = "status")
       }
)
@Where(clause = "deleted_at IS NULL")
public class InsertSlot extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_line_id", nullable = false, length = 36)
    private String productionLineId;

    @Column(name = "production_line_name", length = 100)
    private String productionLineName;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    /**
     * 可用产能 (kg)
     */
    @Column(name = "available_capacity", precision = 10, scale = 2)
    private BigDecimal availableCapacity;

    /**
     * 影响等级: none/low/medium/high
     */
    @Column(name = "impact_level", length = 20)
    @Builder.Default
    private String impactLevel = "none";

    /**
     * 受影响的计划列表 (JSON格式)
     * 格式: [{"planId": "xxx", "delayMinutes": 30}, ...]
     */
    @Column(name = "impacted_plans", columnDefinition = "JSON")
    private String impactedPlans;

    /**
     * 所需人员数
     */
    @Column(name = "required_workers")
    private Integer requiredWorkers;

    /**
     * 可用人员数
     */
    @Column(name = "available_workers")
    private Integer availableWorkers;

    /**
     * 换型成本 (分钟)
     */
    @Column(name = "switch_cost_minutes")
    @Builder.Default
    private Integer switchCostMinutes = 0;

    /**
     * AI推荐分数 (0-100)
     */
    @Column(name = "recommend_score")
    @Builder.Default
    private Integer recommendScore = 0;

    /**
     * AI推荐理由
     */
    @Column(name = "recommendation_reason", columnDefinition = "TEXT")
    private String recommendationReason;

    /**
     * 状态: available/selected/expired
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "available";

    // ==================== 辅助方法 ====================

    /**
     * 判断时段是否可用
     */
    public boolean isAvailable() {
        return "available".equals(status);
    }

    /**
     * 判断是否有影响
     */
    public boolean hasImpact() {
        return !"none".equals(impactLevel);
    }

    /**
     * 判断人员是否充足
     */
    public boolean hasEnoughWorkers() {
        if (requiredWorkers == null || availableWorkers == null) {
            return true;
        }
        return availableWorkers >= requiredWorkers;
    }

    /**
     * 获取时段时长（小时）
     */
    public double getDurationHours() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return java.time.Duration.between(startTime, endTime).toMinutes() / 60.0;
    }

    /**
     * 获取影响等级显示名称
     */
    public String getImpactLevelDisplayName() {
        if (impactLevel == null) {
            return "无";
        }
        switch (impactLevel) {
            case "none":
                return "无";
            case "low":
                return "低";
            case "medium":
                return "中";
            case "high":
                return "高";
            default:
                return impactLevel;
        }
    }
}
