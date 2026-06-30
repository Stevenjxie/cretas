package com.cretas.aims.mapper;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionMode;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/**
 * 生产计划实体映射器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Component
public class ProductionPlanMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * Entity 转 DTO
     */
    public ProductionPlanDTO toDTO(ProductionPlan plan) {
        if (plan == null) {
            return null;
        }
        ProductionPlanDTO dto = ProductionPlanDTO.builder()
                .id(plan.getId())
                .factoryId(plan.getFactoryId())
                .planNumber(plan.getPlanNumber())
                .productTypeId(plan.getProductTypeId())
                .plannedQuantity(plan.getPlannedQuantity())
                .actualQuantity(plan.getActualQuantity())
                // W-07 fix (Round 9): prefer the real plannedDate column; fall back to
                // startTime-derived date for legacy rows created before the column was restored.
                .plannedDate(plan.getPlannedDate() != null
                        ? plan.getPlannedDate()
                        : (plan.getStartTime() != null ? plan.getStartTime().toLocalDate() : null))
                .startTime(plan.getStartTime())
                .endTime(plan.getEndTime())
                .expectedCompletionDate(plan.getExpectedCompletionDate())
                .status(plan.getStatus())
                .statusDisplayName(plan.getStatus() != null ? plan.getStatus().getDisplayName() : null)
                .planType(plan.getPlanType())
                .planTypeDisplayName(getPlanTypeDisplayName(plan.getPlanType()))
                .customerOrderNumber(plan.getCustomerOrderNumber())
                .priority(plan.getPriority())
                .estimatedMaterialCost(plan.getEstimatedMaterialCost())
                .actualMaterialCost(plan.getActualMaterialCost())
                .estimatedLaborCost(plan.getEstimatedLaborCost())
                .actualLaborCost(plan.getActualLaborCost())
                .estimatedEquipmentCost(plan.getEstimatedEquipmentCost())
                .actualEquipmentCost(plan.getActualEquipmentCost())
                .estimatedOtherCost(plan.getEstimatedOtherCost())
                .actualOtherCost(plan.getActualOtherCost())
                .notes(plan.getNotes())
                .createdBy(plan.getCreatedBy())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
        // 设置产品信息
        if (plan.getProductType() != null) {
            String ptName = plan.getProductType().getName();
            dto.setProductName(ptName);
            dto.setProductTypeName(ptName);  // Bug #1b (R2): alias field, previously orphan, frontend may bind to either
            dto.setProductUnit(plan.getProductType().getUnit());
        }
        // 设置创建人姓名
        if (plan.getCreatedByUser() != null) {
            dto.setCreatedByName(plan.getCreatedByUser().getFullName());
        }
        // 计算总成本
        dto.setTotalCost(calculateTotalCost(plan));

        // 设置工厂管理员扩展字段
        dto.setSuggestedProductionLineId(plan.getSuggestedProductionLineId());
        dto.setEstimatedWorkers(plan.getEstimatedWorkers());
        dto.setAssignedSupervisorId(plan.getAssignedSupervisorId());

        // 设置未来计划匹配相关字段
        dto.setAllocatedQuantity(plan.getAllocatedQuantity());
        dto.setIsFullyMatched(plan.getIsFullyMatched());
        dto.setMatchingProgress(plan.getMatchingProgress());
        dto.setRemainingQuantity(plan.getRemainingQuantity());

        // 设置调度员模块扩展字段
        dto.setSourceType(plan.getSourceType());
        dto.setSourceTypeDisplayName(plan.getSourceTypeDisplayName());
        dto.setSourceOrderId(plan.getSourceOrderId());
        // SP5 多 SO 合并: 输出 sourceOrderIds 列表 (null/空 → 遗留数据返空 list)
        dto.setSourceOrderIds(plan.getSourceOrderIds() != null ? plan.getSourceOrderIds() : new java.util.ArrayList<>());
        dto.setSourceOrderItemId(plan.getSourceOrderItemId());
        dto.setSourceCustomerName(plan.getSourceCustomerName());
        dto.setProcessName(plan.getProcessName());
        dto.setBatchDate(plan.getBatchDate());
        dto.setAiConfidence(plan.getAiConfidence());
        dto.setAiConfidenceLevel(plan.getAiConfidenceLevel());
        dto.setForecastReason(plan.getForecastReason());
        dto.setCrValue(plan.getCrValue());
        dto.setIsUrgent(plan.isUrgent(1.0));
        dto.setIsMixedBatch(plan.getIsMixedBatch());
        dto.setMixedBatchType(plan.getMixedBatchType());
        dto.setMixedBatchTypeDisplayName(plan.getMixedBatchTypeDisplayName());
        dto.setRelatedOrders(parseRelatedOrders(plan.getRelatedOrders()));
        dto.setSkipProcessReporting(plan.getSkipProcessReporting());

        // 生产业态
        dto.setProductionMode(plan.getProductionMode() != null
                ? plan.getProductionMode().name()
                : ProductionMode.BY_ORDER.name());

        // 设置强制插单审批字段
        dto.setIsForceInserted(plan.getIsForceInserted());
        dto.setRequiresApproval(plan.getRequiresApproval());
        dto.setApprovalStatus(plan.getApprovalStatus() != null ? plan.getApprovalStatus().name() : null);
        dto.setApproverId(plan.getApproverId());
        dto.setApproverName(plan.getApproverName());
        dto.setApprovedAt(plan.getApprovedAt());
        dto.setApprovalComment(plan.getApprovalComment());
        dto.setForceInsertReason(plan.getForceInsertReason());
        dto.setForceInsertBy(plan.getForceInsertBy());
        dto.setForceInsertedAt(plan.getForceInsertedAt());

        return dto;
    }

    /**
     * 解析关联订单JSON为列表
     */
    private List<String> parseRelatedOrders(String relatedOrdersJson) {
        if (relatedOrdersJson == null || relatedOrdersJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(relatedOrdersJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse relatedOrders JSON: {}", relatedOrdersJson, e);
            return new ArrayList<>();
        }
    }

    /**
     * CreateRequest 转 Entity
     */
    public ProductionPlan toEntity(CreateProductionPlanRequest request, String factoryId, Long createdBy) {
        if (request == null) {
            return null;
        }
        ProductionPlan plan = new ProductionPlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(generatePlanNumber());
        plan.setProductTypeId(request.getProductTypeId());
        plan.setPlannedQuantity(request.getPlannedQuantity());
        // W-07 fix (Round 9): plannedDate column restored; map it on create
        plan.setPlannedDate(request.getPlannedDate());
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setPlanType(request.getPlanType() != null ? request.getPlanType() : ProductionPlanType.FROM_INVENTORY);
        plan.setCustomerOrderNumber(request.getCustomerOrderNumber());
        plan.setPriority(request.getPriority() != null ? request.getPriority() : 5);
        plan.setEstimatedMaterialCost(request.getEstimatedMaterialCost());
        plan.setEstimatedLaborCost(request.getEstimatedLaborCost());
        plan.setEstimatedEquipmentCost(request.getEstimatedEquipmentCost());
        plan.setEstimatedOtherCost(request.getEstimatedOtherCost());
        plan.setNotes(request.getNotes());
        plan.setCreatedBy(createdBy);
        // 设置预计完成日期，默认为计划日期+1天
        if (request.getExpectedCompletionDate() != null) {
            plan.setExpectedCompletionDate(request.getExpectedCompletionDate());
        } else if (request.getPlannedDate() != null) {
            plan.setExpectedCompletionDate(request.getPlannedDate().plusDays(1));
        }

        // 设置工厂管理员扩展字段
        plan.setEstimatedWorkers(request.getEstimatedWorkers());
        plan.setAssignedSupervisorId(request.getAssignedSupervisorId());

        // 设置调度员模块扩展字段
        plan.setSourceType(request.getSourceType() != null ? request.getSourceType() : PlanSourceType.MANUAL);
        plan.setSourceOrderId(request.getSourceOrderId());
        // SP5 多 SO 合并: sourceOrderIds 在 mapper 里预设; 服务层后续可追加/规范化。
        // 此时 sourceOrderId 可能还未回填 (service 层 validateAndEnrichSalesOrderSource 还没跑),
        // 所以 mapper 只先存请求里传的 sourceOrderIds; 服务层负责最终 normalizeSourceOrderIds。
        plan.setSourceOrderIds(request.getSourceOrderIds() != null
                ? new java.util.ArrayList<>(request.getSourceOrderIds())
                : new java.util.ArrayList<>());
        plan.setSourceOrderItemId(request.getSourceOrderItemId());
        plan.setSourceCustomerName(request.getSourceCustomerName());
        plan.setProcessName(request.getProcessName());
        plan.setBatchDate(request.getBatchDate());
        plan.setAiConfidence(request.getAiConfidence());
        plan.setForecastReason(request.getForecastReason());
        plan.setIsMixedBatch(request.getIsMixedBatch() != null ? request.getIsMixedBatch() : false);
        plan.setMixedBatchType(request.getMixedBatchType());
        plan.setRelatedOrders(serializeRelatedOrders(request.getRelatedOrders()));

        // 免工序报工开关 (六扇门 Wave2; Fable 审计修复 2026-06-11 — 多租户安全红线, V20261018_02):
        //   request 显式给 → 采纳; null → 安全默认 false (逐道)。
        //   ⚠️ 工厂级默认值 (F006=true 两点 / 其他=false 逐道) 已在 ProductionPlanServiceImpl.createProductionPlan
        //   里对 null 做了"解析为该工厂默认值"的前置处理 → 走到这里时该字段已非 null。
        //   这里的 false 兜底仅防御"未经 service 解析直接调 mapper"的边缘路径 (过去是 null→true 全系统泛化,
        //   会让其他工厂从 RN/AI 静默变两点 → 丢逐道溯源/成本/出成率/自学习/人效)。安全默认必须是逐道。
        plan.setSkipProcessReporting(request.getSkipProcessReporting() != null
                ? request.getSkipProcessReporting()
                : Boolean.FALSE);

        // 生产业态 — 用 fromString 安全解析; null/blank/非法 → BY_ORDER (向后兼容)
        plan.setProductionMode(ProductionMode.fromString(request.getProductionMode()));

        // 计算CR值
        if (request.getEstimatedWorkDays() != null && request.getExpectedCompletionDate() != null) {
            plan.setCrValue(plan.calculateCrValue(request.getEstimatedWorkDays()));
        }

        return plan;
    }

    /**
     * 序列化关联订单列表为JSON
     */
    private String serializeRelatedOrders(java.util.List<String> relatedOrders) {
        if (relatedOrders == null || relatedOrders.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(relatedOrders);
        } catch (Exception e) {
            log.warn("Failed to serialize relatedOrders: {}", relatedOrders, e);
            return null;
        }
    }

    /**
     * 更新实体
     */
    public void updateEntity(ProductionPlan plan, CreateProductionPlanRequest request) {
        if (request.getProductTypeId() != null) {
            plan.setProductTypeId(request.getProductTypeId());
        }
        if (request.getPlannedQuantity() != null) {
            plan.setPlannedQuantity(request.getPlannedQuantity());
        }
        // W-07 fix (Round 9): plannedDate column restored; map on update too
        if (request.getPlannedDate() != null) {
            plan.setPlannedDate(request.getPlannedDate());
        }
        if (request.getCustomerOrderNumber() != null) {
            plan.setCustomerOrderNumber(request.getCustomerOrderNumber());
        }
        if (request.getPriority() != null) {
            plan.setPriority(request.getPriority());
        }
        // W-06 fix (Round 9, qa-prompt v2.4 Rule 17.2 sweep): planType /
        // processName / batchDate are @Column fields but were previously
        // dropped on update. PUT returned 200 "更新成功" but DB row was
        // untouched for these 3. Null-guard map each like the other fields.
        if (request.getPlanType() != null) {
            plan.setPlanType(request.getPlanType());
        }
        if (request.getProcessName() != null) {
            plan.setProcessName(request.getProcessName());
        }
        if (request.getBatchDate() != null) {
            plan.setBatchDate(request.getBatchDate());
        }
        if (request.getEstimatedMaterialCost() != null) {
            plan.setEstimatedMaterialCost(request.getEstimatedMaterialCost());
        }
        if (request.getEstimatedLaborCost() != null) {
            plan.setEstimatedLaborCost(request.getEstimatedLaborCost());
        }
        if (request.getEstimatedEquipmentCost() != null) {
            plan.setEstimatedEquipmentCost(request.getEstimatedEquipmentCost());
        }
        if (request.getEstimatedOtherCost() != null) {
            plan.setEstimatedOtherCost(request.getEstimatedOtherCost());
        }
        if (request.getNotes() != null) {
            plan.setNotes(request.getNotes());
        }
        if (request.getExpectedCompletionDate() != null) {
            plan.setExpectedCompletionDate(request.getExpectedCompletionDate());
        }
        // 更新工厂管理员扩展字段
        if (request.getEstimatedWorkers() != null) {
            plan.setEstimatedWorkers(request.getEstimatedWorkers());
        }
        if (request.getAssignedSupervisorId() != null) {
            plan.setAssignedSupervisorId(request.getAssignedSupervisorId());
        }
        // 更新调度员模块扩展字段
        if (request.getSourceType() != null) {
            plan.setSourceType(request.getSourceType());
        }
        if (request.getSourceOrderItemId() != null) {
            plan.setSourceOrderItemId(request.getSourceOrderItemId());
        }
        if (request.getSourceOrderId() != null) {
            plan.setSourceOrderId(request.getSourceOrderId());
        }
        // SP5 多 SO 合并: 显式传非空列表时才覆盖 (null = 保留原值; 空列表 = 清空)
        if (request.getSourceOrderIds() != null) {
            plan.setSourceOrderIds(new java.util.ArrayList<>(request.getSourceOrderIds()));
        }
        if (request.getSourceCustomerName() != null) {
            plan.setSourceCustomerName(request.getSourceCustomerName());
        }
        if (request.getAiConfidence() != null) {
            plan.setAiConfidence(request.getAiConfidence());
        }
        if (request.getForecastReason() != null) {
            plan.setForecastReason(request.getForecastReason());
        }
        if (request.getIsMixedBatch() != null) {
            plan.setIsMixedBatch(request.getIsMixedBatch());
        }
        if (request.getMixedBatchType() != null) {
            plan.setMixedBatchType(request.getMixedBatchType());
        }
        if (request.getRelatedOrders() != null) {
            plan.setRelatedOrders(serializeRelatedOrders(request.getRelatedOrders()));
        }
        // 免工序报工开关 (V20261017_01): 仅显式传值才改 (null = 保留原值, 不强转 true);
        // 更新走 null-guard 区别于 create 的"默认 true", 避免编辑其他字段时意外翻转已选模式。
        if (request.getSkipProcessReporting() != null) {
            plan.setSkipProcessReporting(request.getSkipProcessReporting());
        }
        // 重新计算CR值
        if (request.getEstimatedWorkDays() != null) {
            plan.setCrValue(plan.calculateCrValue(request.getEstimatedWorkDays()));
        }
    }

    /**
     * 生成计划编号
     */
    private String generatePlanNumber() {
        return "PLAN-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 获取计划类型显示名称
     */
    private String getPlanTypeDisplayName(ProductionPlanType planType) {
        if (planType == null) {
            return null;
        }
        switch (planType) {
            case FUTURE:
                return "未来计划";
            case FROM_INVENTORY:
                return "基于库存";
            default:
                return planType.name();
        }
    }

    /**
     * 计算总成本
     */
    private BigDecimal calculateTotalCost(ProductionPlan plan) {
        BigDecimal total = BigDecimal.ZERO;
        if (plan.getActualMaterialCost() != null) {
            total = total.add(plan.getActualMaterialCost());
        } else if (plan.getEstimatedMaterialCost() != null) {
            total = total.add(plan.getEstimatedMaterialCost());
        }
        if (plan.getActualLaborCost() != null) {
            total = total.add(plan.getActualLaborCost());
        } else if (plan.getEstimatedLaborCost() != null) {
            total = total.add(plan.getEstimatedLaborCost());
        }
        if (plan.getActualEquipmentCost() != null) {
            total = total.add(plan.getActualEquipmentCost());
        } else if (plan.getEstimatedEquipmentCost() != null) {
            total = total.add(plan.getEstimatedEquipmentCost());
        }
        if (plan.getActualOtherCost() != null) {
            total = total.add(plan.getActualOtherCost());
        } else if (plan.getEstimatedOtherCost() != null) {
            total = total.add(plan.getEstimatedOtherCost());
        }
        return total;
    }
}
