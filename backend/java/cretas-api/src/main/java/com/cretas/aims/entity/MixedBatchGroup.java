package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.MixedBatchType;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 混批分组实体
 * 记录可合并的订单组信息
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Entity
@Table(name = "mixed_batch_groups", indexes = {
        @Index(name = "idx_mbg_factory_status", columnList = "factory_id, status"),
        @Index(name = "idx_mbg_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class MixedBatchGroup extends BaseEntity {

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
     * 混批类型: SAME_MATERIAL(同原料不同客户) / SAME_PROCESS(同工艺不同产品)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", length = 30, nullable = false)
    private MixedBatchType groupType;

    /**
     * 共用原料批次ID (SAME_MATERIAL时使用)
     */
    @Column(name = "material_batch_id", length = 50)
    private String materialBatchId;

    /**
     * 原料批次号 (显示用)
     */
    @Column(name = "material_batch_number", length = 50)
    private String materialBatchNumber;

    /**
     * 原料名称
     */
    @Column(name = "material_name", length = 100)
    private String materialName;

    /**
     * 共用工艺类型 (SAME_PROCESS时使用)
     */
    @Column(name = "process_type", length = 50)
    private String processType;

    /**
     * 工艺名称
     */
    @Column(name = "process_name", length = 100)
    private String processName;

    /**
     * 合并的订单ID列表 (JSON数组)
     */
    @Column(name = "order_ids", columnDefinition = "JSON", nullable = false)
    private String orderIds;

    /**
     * 合并的订单数量
     */
    @Column(name = "order_count")
    private Integer orderCount;

    /**
     * 合并后总数量
     */
    @Column(name = "total_quantity", precision = 10, scale = 2)
    private BigDecimal totalQuantity;

    /**
     * 单位
     */
    @Column(name = "quantity_unit", length = 20)
    private String quantityUnit;

    /**
     * 预计节省换型时间 (分钟)
     */
    @Column(name = "estimated_switch_saving")
    private Integer estimatedSwitchSaving;

    /**
     * 工艺相似度 (0-1)
     */
    @Column(name = "process_similarity", precision = 3, scale = 2)
    private BigDecimal processSimilarity;

    /**
     * 最早交期
     */
    @Column(name = "earliest_deadline")
    private LocalDateTime earliestDeadline;

    /**
     * 最晚交期
     */
    @Column(name = "latest_deadline")
    private LocalDateTime latestDeadline;

    /**
     * 交期间隔 (小时)
     */
    @Column(name = "deadline_gap_hours")
    private Integer deadlineGapHours;

    /**
     * 状态: pending(待确认) / confirmed(已确认) / rejected(已拒绝) / expired(已过期)
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "pending";

    /**
     * AI推荐分数 (0-100)
     */
    @Column(name = "recommend_score")
    private Integer recommendScore;

    /**
     * AI推荐理由
     */
    @Column(name = "recommendation_reason", length = 500)
    private String recommendationReason;

    /**
     * 确认人ID
     */
    @Column(name = "confirmed_by")
    private Long confirmedBy;

    /**
     * 确认时间
     */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /**
     * 拒绝原因
     */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /**
     * 生成的生产计划ID
     */
    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    // ==================== 辅助方法 ====================

    /**
     * 是否待确认
     */
    public boolean isPending() {
        return "pending".equals(this.status);
    }

    /**
     * 是否已确认
     */
    public boolean isConfirmed() {
        return "confirmed".equals(this.status);
    }

    /**
     * 是否已拒绝
     */
    public boolean isRejected() {
        return "rejected".equals(this.status);
    }

    /**
     * 获取混批类型显示名称
     */
    public String getGroupTypeDisplayName() {
        if (this.groupType == null) return "未知";
        switch (this.groupType) {
            case SAME_MATERIAL:
                return "同原料不同客户";
            case SAME_PROCESS:
                return "同工艺不同产品";
            default:
                return this.groupType.name();
        }
    }

    /**
     * 获取状态显示名称
     */
    public String getStatusDisplayName() {
        if (this.status == null) return "未知";
        switch (this.status) {
            case "pending":
                return "待确认";
            case "confirmed":
                return "已确认";
            case "rejected":
                return "已拒绝";
            case "expired":
                return "已过期";
            default:
                return this.status;
        }
    }
}
