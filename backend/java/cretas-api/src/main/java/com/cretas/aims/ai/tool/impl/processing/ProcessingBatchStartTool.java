package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产批次开始工具
 *
 * 启动生产批次，将状态从 PLANNED 更改为 IN_PROGRESS。
 * 需要指定负责人ID，自动记录开始时间。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchStartTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_start";
    }

    @Override
    public String getDescription() {
        return "开始生产批次。将批次状态从「已计划」改为「生产中」，需要指定批次ID和负责人ID。" +
                "适用场景：开始生产任务、启动加工流程、开工确认。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "生产批次ID");
        properties.put("batchId", batchId);

        // supervisorId: 负责人ID（必需）
        Map<String, Object> supervisorId = new HashMap<>();
        supervisorId.put("type", "integer");
        supervisorId.put("description", "生产负责人ID，必须指定当前负责人");
        properties.put("supervisorId", supervisorId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "supervisorId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "supervisorId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行开始生产批次 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");
        Integer supervisorId = getInteger(params, "supervisorId");

        // 2. 调用服务开始生产
        ProductionBatch batch = processingService.startProduction(factoryId, batchId, supervisorId);

        // 3. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNumber", batch.getBatchNumber());
        result.put("status", batch.getStatus().name());
        result.put("supervisorId", batch.getSupervisorId());
        result.put("supervisorName", batch.getSupervisorName());
        result.put("startTime", batch.getStartTime() != null ? batch.getStartTime().toString() : null);
        result.put("productName", batch.getProductName());
        result.put("plannedQuantity", batch.getPlannedQuantity());
        result.put("unit", batch.getUnit());
        result.put("message", String.format("生产批次已开始，批次号: %s，负责人: %s，开始时间: %s",
                batch.getBatchNumber(),
                batch.getSupervisorName() != null ? batch.getSupervisorName() : supervisorId,
                batch.getStartTime()));

        log.info("生产批次开始完成 - 批次ID: {}, 批次号: {}", batch.getId(), batch.getBatchNumber());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要开始哪个生产批次？请提供批次ID或批次号。";
            case "supervisorId":
                return "请问谁负责这个批次的生产？请提供负责人ID。";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            case "supervisorId":
                return "负责人ID";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "factory_admin".equals(userRole) ||
                "production_manager".equals(userRole) ||
                "production_supervisor".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
