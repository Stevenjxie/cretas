package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.MixedBatchType;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 混批规则配置实体
 * 配置工厂的混批合并规则
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Entity
@Table(name = "mixed_batch_rules", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mbr_factory_type", columnNames = {"factory_id", "rule_type"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class MixedBatchRule extends BaseEntity {

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
     * 规则类型: SAME_MATERIAL / SAME_PROCESS
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", length = 30, nullable = false)
    private MixedBatchType ruleType;

    /**
     * 是否启用
     */
    @Column(name = "is_enabled")
    @Builder.Default
    private Boolean isEnabled = true;

    /**
     * 最大交期间隔 (小时)
     * 只有交期间隔在此范围内的订单才会被考虑合并
     */
    @Column(name = "max_deadline_gap_hours")
    @Builder.Default
    private Integer maxDeadlineGapHours = 24;

    /**
     * 最小换型节省时间 (分钟)
     * 只有节省时间超过此阈值才推荐合并
     */
    @Column(name = "min_switch_saving_minutes")
    @Builder.Default
    private Integer minSwitchSavingMinutes = 10;

    /**
     * 工艺相似度阈值 (0-1)
     * SAME_PROCESS 类型使用，相似度超过此值才可合并
     */
    @Column(name = "process_similarity_threshold", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal processSimilarityThreshold = new BigDecimal("0.80");

    /**
     * 最大合并订单数
     * 单个混批组最多合并的订单数量
     */
    @Column(name = "max_orders_per_group")
    @Builder.Default
    private Integer maxOrdersPerGroup = 10;

    /**
     * 最大合并数量 (kg)
     * 单个混批组的最大总数量
     */
    @Column(name = "max_total_quantity", precision = 10, scale = 2)
    private BigDecimal maxTotalQuantity;

    /**
     * 是否自动合并
     * 开启后满足条件的订单会自动创建混批组
     */
    @Column(name = "auto_merge")
    @Builder.Default
    private Boolean autoMerge = false;

    /**
     * 是否需要审批
     * 开启后确认混批需要审批流程
     */
    @Column(name = "require_approval")
    @Builder.Default
    private Boolean requireApproval = false;

    /**
     * 通知配置 (JSON)
     * 配置谁在什么情况下收到通知
     */
    @Column(name = "notification_config", columnDefinition = "JSON")
    private String notificationConfig;

    /**
     * 备注
     */
    @Column(name = "notes", length = 500)
    private String notes;

    // ==================== 辅助方法 ====================

    /**
     * 获取规则类型显示名称
     */
    public String getRuleTypeDisplayName() {
        if (this.ruleType == null) return "未知";
        switch (this.ruleType) {
            case SAME_MATERIAL:
                return "同原料不同客户";
            case SAME_PROCESS:
                return "同工艺不同产品";
            default:
                return this.ruleType.name();
        }
    }

    /**
     * 检查订单是否符合交期间隔要求
     */
    public boolean isWithinDeadlineGap(int gapHours) {
        return this.maxDeadlineGapHours != null && gapHours <= this.maxDeadlineGapHours;
    }

    /**
     * 检查节省时间是否满足最小要求
     */
    public boolean meetsSavingThreshold(int savingMinutes) {
        return this.minSwitchSavingMinutes == null || savingMinutes >= this.minSwitchSavingMinutes;
    }

    /**
     * 检查工艺相似度是否满足要求
     */
    public boolean meetsSimilarityThreshold(BigDecimal similarity) {
        if (this.processSimilarityThreshold == null) return true;
        return similarity != null && similarity.compareTo(this.processSimilarityThreshold) >= 0;
    }
}
