package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产批次恢复工具
 *
 * 恢复已暂停的生产批次，将状态从 PAUSED 更改为 IN_PROGRESS。
 * 用于解决暂停原因后继续生产。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchResumeTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_resume";
    }

    @Override
    public String getDescription() {
        return "恢复生产批次。将批次状态从「已暂停」改为「生产中」，继续之前暂停的生产。" +
                "适用场景：故障修复后恢复、原料补充后恢复、问题解决后恢复生产。";
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
        log.info("执行恢复生产批次 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");

        // 2. 调用服务恢复生产
        ProductionBatch batch = processingService.resumeProduction(factoryId, batchId);

        // 3. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNumber", batch.getBatchNumber());
        result.put("status", batch.getStatus().name());
        result.put("productName", batch.getProductName());
        result.put("supervisorName", batch.getSupervisorName());
        result.put("plannedQuantity", batch.getPlannedQuantity());
        result.put("unit", batch.getUnit());
        result.put("message", String.format("生产批次已恢复，批次号: %s，当前状态: %s",
                batch.getBatchNumber(), batch.getStatus().name()));

        log.info("生产批次恢复完成 - 批次ID: {}, 批次号: {}", batch.getId(), batch.getBatchNumber());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要恢复哪个暂停的生产批次？请提供批次ID或批次号。";
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
