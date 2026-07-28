package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产批次详情工具
 *
 * 获取指定生产批次的详细信息，包括基本信息、生产数据、成本数据、质量指标等。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchDetailTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_detail";
    }

    @Override
    public String getDescription() {
        return "获取生产批次详情。返回批次的完整信息，包括基本信息、生产数据、成本数据、质量指标等。" +
                "适用场景：查看批次详情、追踪生产状态、查询产量数据。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "生产批次ID或批次号");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行获取生产批次详情 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");

        // 2. 调用服务获取详情
        ProductionBatch batch = processingService.getBatchById(factoryId, batchId);

        if (batch == null) {
            throw new IllegalArgumentException("未找到指定的生产批次: " + batchId);
        }

        // 3. 构建返回结果
        Map<String, Object> result = new HashMap<>();

        // 基本信息
        result.put("batchId", batch.getId());
        result.put("batchNumber", batch.getBatchNumber());
        result.put("productTypeId", batch.getProductTypeId());
        result.put("productName", batch.getProductName());
        result.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
        result.put("qualityStatus", batch.getQualityStatus() != null ? batch.getQualityStatus().name() : null);

        // 计划与实际数据
        Map<String, Object> productionData = new HashMap<>();
        productionData.put("plannedQuantity", batch.getPlannedQuantity());
        productionData.put("actualQuantity", batch.getActualQuantity());
        productionData.put("goodQuantity", batch.getGoodQuantity());
        productionData.put("defectQuantity", batch.getDefectQuantity());
        productionData.put("unit", batch.getUnit());
        result.put("productionData", productionData);

        // 时间信息
        Map<String, Object> timeInfo = new HashMap<>();
        timeInfo.put("startTime", batch.getStartTime() != null ? batch.getStartTime().toString() : null);
        timeInfo.put("endTime", batch.getEndTime() != null ? batch.getEndTime().toString() : null);
        timeInfo.put("workDurationMinutes", batch.getWorkDurationMinutes());
        result.put("timeInfo", timeInfo);

        // 人员信息
        Map<String, Object> personnel = new HashMap<>();
        personnel.put("supervisorId", batch.getSupervisorId());
        personnel.put("supervisorName", batch.getSupervisorName());
        personnel.put("workerCount", batch.getWorkerCount());
        personnel.put("createdBy", batch.getCreatedBy());
        result.put("personnel", personnel);

        // 设备信息
        Map<String, Object> equipment = new HashMap<>();
        equipment.put("equipmentId", batch.getEquipmentId());
        equipment.put("equipmentName", batch.getEquipmentName());
        result.put("equipment", equipment);

        // 成本数据
        Map<String, Object> costData = new HashMap<>();
        costData.put("materialCost", batch.getMaterialCost());
        costData.put("laborCost", batch.getLaborCost());
        costData.put("equipmentCost", batch.getEquipmentCost());
        costData.put("otherCost", batch.getOtherCost());
        costData.put("totalCost", batch.getTotalCost());
        costData.put("unitCost", batch.getUnitCost());
        result.put("costData", costData);

        // 质量指标
        Map<String, Object> qualityMetrics = new HashMap<>();
        qualityMetrics.put("yieldRate", batch.getYieldRate());
        qualityMetrics.put("efficiency", batch.getEfficiency());
        result.put("qualityMetrics", qualityMetrics);

        // 其他信息
        result.put("productionPlanId", batch.getProductionPlanId());
        result.put("notes", batch.getNotes());
        result.put("photoRequired", batch.getPhotoRequired());
        result.put("sopConfigId", batch.getSopConfigId());

        // 添加摘要信息
        result.put("message", String.format("批次号: %s，产品: %s，状态: %s，计划产量: %s %s",
                batch.getBatchNumber(),
                batch.getProductName(),
                batch.getStatus() != null ? batch.getStatus().name() : "N/A",
                batch.getPlannedQuantity(),
                batch.getUnit()));

        log.info("获取生产批次详情完成 - 批次ID: {}, 批次号: {}", batch.getId(), batch.getBatchNumber());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要查看哪个生产批次的详情？请提供批次ID或批次号。";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    public boolean requiresPermission() {
        return false; // 查询类工具不需要特殊权限
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
