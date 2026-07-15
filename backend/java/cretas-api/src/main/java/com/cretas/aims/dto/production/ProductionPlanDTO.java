package com.cretas.aims.dto.production;

import com.cretas.aims.entity.enums.MixedBatchType;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.security.PriceSensitive;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生产计划数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "生产计划信息")
public class ProductionPlanDTO {

    @Schema(description = "计划ID")
    private String id;

    @Schema(description = "工厂ID")
    private String factoryId;

    @Schema(description = "计划编号")
    private String planNumber;

    @Schema(description = "产品类型ID")
    private String productTypeId;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "产品单位")
    private String productUnit;

    @Schema(description = "计划数量")
    private BigDecimal plannedQuantity;

    @Schema(description = "计划成品单位快照")
    private String plannedUnit;
    private BigDecimal plannedNetWeightGrams;

    private String workflowSelectionMode;
    private Long selectedWorkflowId;
    private Integer selectedWorkflowVersion;
    private String selectedBomRecipeId;
    private Integer selectedBomVersion;

    @Schema(description = "实际数量")
    private BigDecimal actualQuantity;

    @Schema(description = "计划日期 (从startTime自动推导，如果startTime存在)")
    private LocalDate plannedDate;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "预计完成日期")
    private LocalDate expectedCompletionDate;

    @Schema(description = "状态")
    private ProductionPlanStatus status;

    @Schema(description = "状态显示名称")
    private String statusDisplayName;

    @Schema(description = "计划类型 (FUTURE=未来计划, FROM_INVENTORY=基于库存)")
    private ProductionPlanType planType;

    @Schema(description = "计划类型显示名称")
    private String planTypeDisplayName;

    @Schema(description = "转换率是否已配置")
    private Boolean conversionRateConfigured;

    @Schema(description = "转换率")
    private BigDecimal conversionRate;

    @Schema(description = "损耗率")
    private BigDecimal wastageRate;

    @Schema(description = "客户订单号")
    private String customerOrderNumber;

    @Schema(description = "优先级")
    private Integer priority;

    @PriceSensitive
    @Schema(description = "预估材料成本")
    private BigDecimal estimatedMaterialCost;

    @PriceSensitive
    @Schema(description = "实际材料成本")
    private BigDecimal actualMaterialCost;

    @PriceSensitive
    @Schema(description = "预估人工成本")
    private BigDecimal estimatedLaborCost;

    @PriceSensitive
    @Schema(description = "实际人工成本")
    private BigDecimal actualLaborCost;

    @PriceSensitive
    @Schema(description = "预估设备成本")
    private BigDecimal estimatedEquipmentCost;

    @PriceSensitive
    @Schema(description = "实际设备成本")
    private BigDecimal actualEquipmentCost;

    @PriceSensitive
    @Schema(description = "预估其他成本")
    private BigDecimal estimatedOtherCost;

    @PriceSensitive
    @Schema(description = "实际其他成本")
    private BigDecimal actualOtherCost;

    @PriceSensitive
    @Schema(description = "总成本")
    private BigDecimal totalCost;

    @Schema(description = "备注")
    private String notes;

    @Schema(description = "创建人ID")
    private Long createdBy;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    // ======= 未来计划匹配相关字段 =======

    @Schema(description = "已分配的原料数量")
    private BigDecimal allocatedQuantity;

    @Schema(description = "是否完全匹配")
    private Boolean isFullyMatched;

    @Schema(description = "匹配进度百分比")
    private Integer matchingProgress;

    @Schema(description = "剩余需求量")
    private BigDecimal remainingQuantity;

    // ======= 工厂管理员扩展字段 =======

    @Schema(description = "建议产线ID")
    private String suggestedProductionLineId;

    @Schema(description = "建议产线名称")
    private String suggestedProductionLineName;

    @Schema(description = "预计工人数")
    private Integer estimatedWorkers;

    @Schema(description = "指派车间主管ID")
    private Long assignedSupervisorId;

    @Schema(description = "指派车间主管名称")
    private String assignedSupervisorName;

    // ======= 调度员模块扩展字段 =======

    @Schema(description = "计划来源类型: CUSTOMER_ORDER/AI_FORECAST/SAFETY_STOCK/MANUAL/URGENT_INSERT")
    private PlanSourceType sourceType;

    @Schema(description = "计划来源显示名称")
    private String sourceTypeDisplayName;

    @Schema(description = "关联订单ID")
    private String sourceOrderId;

    /**
     * SP5 多 SO 合并工单: 本计划覆盖的全部销售订单 ID 列表.
     * 单 SO 场景: [sourceOrderId]; 多 SO 合并: [id1, id2, ...].
     * null / 空 = 遗留数据 (未迁移行).
     */
    @Schema(description = "SP5 合并工单覆盖的全部销售订单ID列表 (多SO合并; 单SO=[sourceOrderId])")
    private List<String> sourceOrderIds;

    @Schema(description = "raw-centric 多成品: 本计划要产的终端成品 productTypeId 列表 (null=非多成品计划)")
    private List<String> targetFinishedGoodIds;

    @Schema(description = "关联销售订单行ID (P0-12)")
    private String sourceOrderItemId;

    @Schema(description = "客户名称")
    private String sourceCustomerName;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "批次日期")
    private LocalDate batchDate;

    @Schema(description = "AI预测置信度 (0-100)")
    private Integer aiConfidence;

    @Schema(description = "AI预测置信度等级: HIGH/MEDIUM/LOW")
    private String aiConfidenceLevel;

    @Schema(description = "预测原因 (如: 冬季火锅需求+15%)")
    private String forecastReason;

    @Schema(description = "CR值 (Critical Ratio): (交期-今日) / 工期，越小越紧急")
    private BigDecimal crValue;

    @Schema(description = "是否紧急 (CR < 1)")
    private Boolean isUrgent;

    @Schema(description = "是否混批")
    private Boolean isMixedBatch;

    @Schema(description = "混批类型: SAME_MATERIAL/SAME_PROCESS")
    private MixedBatchType mixedBatchType;

    @Schema(description = "混批类型显示名称")
    private String mixedBatchTypeDisplayName;

    @Schema(description = "混批关联订单ID列表")
    private List<String> relatedOrders;

    @Schema(description = "免工序报工开关 (true=批次级两点报工 领料+产出; false=逐道)")
    private Boolean skipProcessReporting;

    // ======= 强制插单审批字段 =======

    @Schema(description = "是否为强制插单")
    private Boolean isForceInserted;

    @Schema(description = "是否需要审批")
    private Boolean requiresApproval;

    @Schema(description = "审批状态: PENDING/APPROVED/REJECTED")
    private String approvalStatus;

    @Schema(description = "审批人ID")
    private Long approverId;

    @Schema(description = "审批人姓名")
    private String approverName;

    @Schema(description = "审批时间")
    private LocalDateTime approvedAt;

    @Schema(description = "审批备注/理由")
    private String approvalComment;

    @Schema(description = "强制插单原因")
    private String forceInsertReason;

    @Schema(description = "强制插单操作人ID")
    private Long forceInsertBy;

    @Schema(description = "强制插单时间")
    private LocalDateTime forceInsertedAt;

    // ======= 紧急调度相关字段 =======

    @Schema(description = "当前完成概率 (0-1)")
    private BigDecimal currentProbability;

    @Schema(description = "概率更新时间")
    private LocalDateTime probabilityUpdatedAt;

    @Schema(description = "产品类型名称")
    private String productTypeName;

    // ======= 排产锁定 (Issue #759) =======

    @Schema(description = "是否已锁定 (锁定后进入排产保护阶段, PUT /production-plans/{planId} 会 409)")
    private Boolean isLocked;

    @Schema(description = "锁定原因")
    private String lockReason;

}
