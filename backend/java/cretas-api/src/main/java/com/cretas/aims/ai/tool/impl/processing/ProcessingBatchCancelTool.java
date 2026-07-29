package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产批次取消工具
 *
 * 取消生产批次，将状态更改为 CANCELLED。
 * 需要提供取消原因，便于后续追溯和分析。
 * 已完成的批次不能取消。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchCancelTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_cancel";
    }

    @Override
    public String getDescription() {
        return "取消生产批次。将批次状态改为「已取消」，需要指定批次ID和取消原因。" +
                "注意：已完成的批次不能取消。适用场景：订单取消、计划变更、原料问题取消生产。";
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

        // reason: 取消原因（必需）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "取消原因，如：订单取消、计划变更、原料问题等");
        reason.put("maxLength", 500);
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "reason"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "reason");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行取消生产批次 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");
        String reason = getString(params, "reason");

        // 2. 调用服务取消生产
        ProductionBatch batch = processingService.cancelProduction(factoryId, batchId, reason);

        // 3. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNumber", batch.getBatchNumber());
        result.put("status", batch.getStatus().name());
        result.put("productName", batch.getProductName());
        result.put("cancelReason", reason);
        result.put("plannedQuantity", batch.getPlannedQuantity());
        result.put("unit", batch.getUnit());
        result.put("message", String.format("生产批次已取消，批次号: %s，原因: %s",
                batch.getBatchNumber(), reason));

        log.info("生产批次取消完成 - 批次ID: {}, 批次号: {}, 原因: {}", batch.getId(), batch.getBatchNumber(), reason);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要取消哪个生产批次？请提供批次ID或批次号。";
            case "reason":
                return "请说明取消生产的原因（如：订单取消、计划变更、原料问题等）。";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            case "reason":
                return "取消原因";
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
                "production_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
