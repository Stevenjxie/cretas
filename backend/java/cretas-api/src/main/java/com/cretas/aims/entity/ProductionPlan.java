package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.MixedBatchType;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;
/**
 * 生产计划实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "productType", "createdByUser", "materialConsumptions", "batchUsages"})
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "production_plans",
       indexes = {
           @Index(name = "idx_plan_factory", columnList = "factory_id"),
           @Index(name = "idx_plan_status", columnList = "status"),
           @Index(name = "idx_plan_date", columnList = "planned_date")
       }
)
@Where(clause = "deleted_at IS NULL")
public class ProductionPlan extends BaseEntity {

    /** Sprint3-E F-VFLAG-1: 凭证生成状态. UNCREATED → PENDING → CREATED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vflag", nullable = false, length = 20)
    private com.cretas.aims.entity.enums.VoucherFlag vflag = com.cretas.aims.entity.enums.VoucherFlag.UNCREATED;
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "plan_number", nullable = false, unique = true, length = 50)
    private String planNumber;
    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;
    @Column(name = "planned_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal plannedQuantity;
    /** Unit snapshot of planned finished-product output quantity. */
    @Column(name = "planned_unit", nullable = false, length = 32)
    private String plannedUnit = "kg";
    @Column(name = "actual_quantity", precision = 10, scale = 2)
    private BigDecimal actualQuantity;
    // W-07 fix (Round 9): restored as nullable column (was commented out - DB didn't have it,
    // but DTO still @NotNull on create path + FE required field → silent-drop + user-facing phantom).
    // Nullable to keep legacy rows (which populated startTime only) valid.
    @Column(name = "planned_date")
    private LocalDate plannedDate;
    @Column(name = "start_time")
    private LocalDateTime startTime;
    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "expected_completion_date")
    private LocalDate expectedCompletionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductionPlanStatus status = ProductionPlanStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type")
    private ProductionPlanType planType = ProductionPlanType.FROM_INVENTORY;
    @Column(name = "customer_order_number", length = 100)
    private String customerOrderNumber;
    @Column(name = "priority")
    private Integer priority;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // 未来计划自动匹配相关字段
    @Column(name = "allocated_quantity", precision = 10, scale = 2)
    private BigDecimal allocatedQuantity = BigDecimal.ZERO;  // 已分配的原料数量

    @Column(name = "is_fully_matched")
    private Boolean isFullyMatched = false;  // 是否完全匹配

    // 成本相关字段 (@PriceSensitive — stripped for roles lacking procurement:price:view)
    @PriceSensitive
    @Column(name = "estimated_material_cost", precision = 10, scale = 2)
    private BigDecimal estimatedMaterialCost;
    @PriceSensitive
    @Column(name = "actual_material_cost", precision = 10, scale = 2)
    private BigDecimal actualMaterialCost;
    @PriceSensitive
    @Column(name = "estimated_labor_cost", precision = 10, scale = 2)
    private BigDecimal estimatedLaborCost;
    @PriceSensitive
    @Column(name = "actual_labor_cost", precision = 10, scale = 2)
    private BigDecimal actualLaborCost;
    @PriceSensitive
    @Column(name = "estimated_equipment_cost", precision = 10, scale = 2)
    private BigDecimal estimatedEquipmentCost;
    @PriceSensitive
    @Column(name = "actual_equipment_cost", precision = 10, scale = 2)
    private BigDecimal actualEquipmentCost;
    @PriceSensitive
    @Column(name = "estimated_other_cost", precision = 10, scale = 2)
    private BigDecimal estimatedOtherCost;
    @PriceSensitive
    @Column(name = "actual_other_cost", precision = 10, scale = 2)
    private BigDecimal actualOtherCost;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ==================== 工厂管理员扩展字段 ====================

    @Column(name = "suggested_production_line_id", length = 36)
    private String suggestedProductionLineId;

    @Column(name = "estimated_workers")
    private Integer estimatedWorkers;

    @Column(name = "assigned_supervisor_id")
    private Long assignedSupervisorId;

    // ==================== 锁定字段 (Issue #759, 2026-05-17) ====================

    /**
     * 是否锁定. true = 不可编辑数量/日期/取消.
     * 配合 lock/unlock endpoint 使用 (PR #755 frontend banner + button).
     */
    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;

    @Column(name = "lock_reason", columnDefinition = "TEXT")
    private String lockReason;

    @Column(name = "locked_at")
    private java.time.LocalDateTime lockedAt;

    @Column(name = "locked_by")
    private Long lockedBy;

    // ==================== 调度员模块扩展字段 ====================

    /**
     * 计划来源类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    private PlanSourceType sourceType = PlanSourceType.MANUAL;

    // ==================== SP2 二次加工字段 ====================

    /**
     * SP2 计划来源子类型: NORMAL(默认正常生产) | SECONDARY(跨单独立二次加工).
     * <p>不同于已有 source_type (PlanSourceType), 本字段专门标记"使用半成品库存作为原料输入"的二次加工场景.
     * V20260910_11 新增列.
     */
    @Column(name = "plan_source_type", length = 30)
    private String planSourceType = "NORMAL";

    /**
     * SP2 二次加工: 跨单领用的半成品库存 ID (FK → semi_finished_inventory.id).
     * <p>开工时从该 SemiFinishedInventory 行扣减 availableQuantity.
     * 仅 planSourceType=SECONDARY 时有值; 正常生产计划为 null.
     * V20260910_11 新增列.
     */
    @Column(name = "secondary_source_wip_id")
    private Long secondarySourceWipId;

    // ==================== 免工序报工开关 (六扇门 Wave2 升级, V20261017_01) ====================

    /**
     * 免工序报工开关 (Steve 2026-06-10 拍板, 取代 #690 per-工序属性做法).
     * <p>false (默认/迁移回填) = 逐道: 现有工序级报工链, 行为完全不变 (向后兼容铁律)。
     * <p>true = 批次级两点报工: 领料报工(入) + 产出报工(出); 人工两点不报 (登下一期, 期间级分摊);
     * 成本只含料; 出成率 = 产出/领料。spawn 时生成 2 个批次级哨兵任务 (MATERIAL_INPUT/FINAL_OUTPUT),
     * 不生成工序 task。
     * <p>createProductionPlan API 对"新建计划"默认 true (六扇门 want); 产品 0 工序时强制 true。
     */
    @Column(name = "skip_process_reporting", nullable = false)
    private Boolean skipProcessReporting = false;

    /**
     * 关联订单ID
     */
    @Column(name = "source_order_id", length = 50)
    private String sourceOrderId;

    /**
     * SP5: 本计划覆盖的全部销售订单ID列表 (JSONB string array).
     * 支持"合并计划"场景 (一张计划覆盖多张订单).
     * 单订单场景: [sourceOrderId]; 多订单合并: [id1, id2, ...].
     * null / 空 = 遗留数据 (未迁移).
     */
    @Type(io.hypersistence.utils.hibernate.type.json.JsonBinaryType.class)
    @Column(name = "source_order_ids", columnDefinition = "jsonb")
    private java.util.List<String> sourceOrderIds = new java.util.ArrayList<>();

    /**
     * 关联销售订单行ID (P0-12 字段粒度修正)
     * 客户原话 4216s: "关联销售订单产品" — 关联到行项目而非订单主表
     */
    @Column(name = "source_order_item_id", length = 50)
    private String sourceOrderItemId;

    /**
     * 客户名称
     */
    @Column(name = "source_customer_name", length = 100)
    private String sourceCustomerName;

    @Column(name = "process_name", length = 200)
    private String processName;

    @Column(name = "batch_date")
    private LocalDate batchDate;

    /**
     * AI预测置信度 (0-100)
     */
    @Column(name = "ai_confidence")
    private Integer aiConfidence;

    /**
     * 预测原因 (如: 冬季火锅需求+15%)
     */
    @Column(name = "forecast_reason", length = 255)
    private String forecastReason;

    /**
     * CR值 (Critical Ratio)
     * CR = (交期-今日) / 工期，越小越紧急
     */
    @Column(name = "cr_value", precision = 5, scale = 2)
    private BigDecimal crValue;

    /**
     * 是否混批
     */
    @Column(name = "is_mixed_batch")
    private Boolean isMixedBatch = false;

    /**
     * 混批类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mixed_batch_type", length = 30)
    private MixedBatchType mixedBatchType;

    /**
     * 混批关联订单 (JSON格式)
     * 格式: ["ORD-001", "ORD-002", "ORD-003"]
     */
    @Column(name = "related_orders", columnDefinition = "JSON")
    private String relatedOrders;

    // ==================== 紧急状态监控字段 ====================

    /**
     * 当前完成概率 (0-1)
     * 基于多因素加权计算：
     * - CR值权重: 40%
     * - 材料匹配权重: 30%
     * - AI置信度权重: 20%
     * - 混批影响权重: 10%
     */
    @Column(name = "current_probability", precision = 5, scale = 4)
    private BigDecimal currentProbability;

    /**
     * 概率最后更新时间
     * 用于判断概率是否过期（超过1小时需重新计算）
     */
    @Column(name = "probability_updated_at")
    private LocalDateTime probabilityUpdatedAt;

    // 关联关系
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.BatchSize(size = 10)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.BatchSize(size = 20)
    private ProductType productType;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    @org.hibernate.annotations.BatchSize(size = 10)
    private User createdByUser;

    @JsonIgnore
    @OneToMany(mappedBy = "productionPlan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialConsumption> materialConsumptions = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "productionPlan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductionPlanBatchUsage> batchUsages = new ArrayList<>();

    /**
     * 计算剩余需要匹配的原料数量
     */
    public BigDecimal getRemainingQuantity() {
        if (plannedQuantity == null) return BigDecimal.ZERO;
        if (allocatedQuantity == null) return plannedQuantity;
        return plannedQuantity.subtract(allocatedQuantity);
    }

    /**
     * 计算匹配进度百分比
     */
    public Integer getMatchingProgress() {
        if (plannedQuantity == null || plannedQuantity.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        if (allocatedQuantity == null) return 0;
        return allocatedQuantity.multiply(new BigDecimal(100))
                .divide(plannedQuantity, 0, java.math.RoundingMode.HALF_UP)
                .intValue();
    }

    // ==================== 调度员模块辅助方法 ====================

    /**
     * 计算CR值 (Critical Ratio)
     * CR = (交期-今日) / 预估工期
     * CR < 1 表示紧急, CR > 1 表示有余裕
     *
     * @param estimatedWorkDays 预估工期（天数）
     * @return CR值
     */
    public BigDecimal calculateCrValue(int estimatedWorkDays) {
        if (expectedCompletionDate == null || estimatedWorkDays <= 0) {
            return null;
        }
        long daysUntilDeadline = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.now(), expectedCompletionDate);
        if (daysUntilDeadline < 0) {
            return BigDecimal.ZERO; // 已超期
        }
        return new BigDecimal(daysUntilDeadline)
                .divide(new BigDecimal(estimatedWorkDays), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 判断是否紧急 (双重标准)
     * 条件1: CR值 < 1 (时间紧急)
     * 条件2: 完成概率 < 阈值 (资源不足)
     *
     * @param threshold 紧急阈值 (0-1之间)
     * @return true表示紧急，false表示正常
     */
    public boolean isUrgent(double threshold) {
        // 时间紧急：CR < 1
        if (crValue != null && crValue.compareTo(BigDecimal.ONE) < 0) {
            return true;
        }
        // 资源不足：概率 < 阈值
        if (currentProbability != null &&
            currentProbability.compareTo(new BigDecimal(threshold)) < 0) {
            return true;
        }
        return false;
    }

    // ==================== 审批流程字段 ====================

    /**
     * 是否为强制插单
     */
    @Column(name = "is_force_inserted")
    private Boolean isForceInserted = false;

    /**
     * 是否需要审批
     */
    @Column(name = "requires_approval")
    private Boolean requiresApproval = false;

    /**
     * 审批状态
     * PENDING - 待审批
     * APPROVED - 已批准
     * REJECTED - 已拒绝
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20)
    private ApprovalStatus approvalStatus;

    /**
     * 审批人ID
     */
    @Column(name = "approver_id")
    private Long approverId;

    /**
     * 审批人姓名
     */
    @Column(name = "approver_name", length = 50)
    private String approverName;

    /**
     * 审批时间
     */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * 审批备注/理由
     */
    @Column(name = "approval_comment", length = 500)
    private String approvalComment;

    /**
     * 强制插单原因
     */
    @Column(name = "force_insert_reason", length = 500)
    private String forceInsertReason;

    /**
     * 强制插单操作人ID
     */
    @Column(name = "force_insert_by")
    private Long forceInsertBy;

    /**
     * 强制插单时间
     */
    @Column(name = "force_inserted_at")
    private LocalDateTime forceInsertedAt;

    /**
     * 审批状态枚举
     */
    public enum ApprovalStatus {
        PENDING,    // 待审批
        APPROVED,   // 已批准
        REJECTED    // 已拒绝
    }

    /**
     * 检查概率是否过期（超过1小时）
     * 过期的概率需要重新计算
     *
     * @return true表示过期，需要重新计算；false表示有效
     */
    public boolean isProbabilityStale() {
        if (probabilityUpdatedAt == null) {
            return true;  // 从未计算过，需要计算
        }
        return probabilityUpdatedAt.isBefore(LocalDateTime.now().minusHours(1));
    }

    /**
     * 判断是否来自客户订单
     */
    public boolean isFromCustomerOrder() {
        return sourceType == PlanSourceType.CUSTOMER_ORDER;
    }

    /**
     * 判断是否来自AI预测
     */
    public boolean isFromAiPrediction() {
        return sourceType == PlanSourceType.AI_FORECAST;
    }

    /**
     * 获取计划来源显示名称
     */
    public String getSourceTypeDisplayName() {
        return sourceType != null ? sourceType.getDisplayName() : "手动创建";
    }

    /**
     * 获取混批类型显示名称
     */
    public String getMixedBatchTypeDisplayName() {
        return mixedBatchType != null ? mixedBatchType.getDisplayName() : null;
    }

    /**
     * 获取AI预测置信度等级
     * @return HIGH(>85%), MEDIUM(60-85%), LOW(<60%)
     */
    public String getAiConfidenceLevel() {
        if (aiConfidence == null) {
            return null;
        }
        if (aiConfidence >= 85) {
            return "HIGH";
        } else if (aiConfidence >= 60) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    // ==================== 审批辅助方法 ====================

    /**
     * 判断是否为待审批状态
     */
    public boolean isPendingApproval() {
        return Boolean.TRUE.equals(requiresApproval) &&
               approvalStatus == ApprovalStatus.PENDING;
    }

    /**
     * 判断是否已批准
     */
    public boolean isApproved() {
        return approvalStatus == ApprovalStatus.APPROVED;
    }

    /**
     * 判断是否已拒绝
     */
    public boolean isRejected() {
        return approvalStatus == ApprovalStatus.REJECTED;
    }

    /**
     * 批准操作
     *
     * @param approverId   审批人ID
     * @param approverName 审批人姓名
     * @param comment      审批备注
     */
    public void approve(Long approverId, String approverName, String comment) {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.approverId = approverId;
        this.approverName = approverName;
        this.approvalComment = comment;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 拒绝操作
     *
     * @param approverId   审批人ID
     * @param approverName 审批人姓名
     * @param comment      拒绝理由
     */
    public void reject(Long approverId, String approverName, String comment) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.approverId = approverId;
        this.approverName = approverName;
        this.approvalComment = comment;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 标记为强制插单
     *
     * @param operatorId 操作人ID
     * @param reason     强制插单原因
     * @param needsApproval 是否需要审批
     */
    public void markAsForceInsert(Long operatorId, String reason, boolean needsApproval) {
        this.isForceInserted = true;
        this.forceInsertBy = operatorId;
        this.forceInsertReason = reason;
        this.forceInsertedAt = LocalDateTime.now();
        this.requiresApproval = needsApproval;
        if (needsApproval) {
            this.approvalStatus = ApprovalStatus.PENDING;
        }
    }

    /**
     * 获取审批状态显示名称
     */
    public String getApprovalStatusDisplayName() {
        if (approvalStatus == null) {
            return "无需审批";
        }
        switch (approvalStatus) {
            case PENDING:
                return "待审批";
            case APPROVED:
                return "已批准";
            case REJECTED:
                return "已拒绝";
            default:
                return "未知";
        }
    }
}
