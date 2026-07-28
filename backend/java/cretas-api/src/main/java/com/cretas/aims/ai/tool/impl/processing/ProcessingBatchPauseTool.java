package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产批次暂停工具
 *
 * 暂停正在进行的生产批次，将状态从 IN_PROGRESS 更改为 PAUSED。
 * 需要提供暂停原因，便于后续追溯和分析。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchPauseTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_pause";
    }

    @Override
    public String getDescription() {
        return "暂停生产批次。将批次状态从「生产中」改为「已暂停」，需要指定批次ID和暂停原因。" +
                "适用场景：设备故障暂停、原料不足暂停、质量问题暂停、其他原因暂停。";
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

        // reason: 暂停原因（必需）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "暂停原因，如：设备故障、原料不足、质量问题、人员调配等");
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
        log.info("执行暂停生产批次 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");
        String reason = getString(params, "reason");

        // 2. 调用服务暂停生产
        ProductionBatch batch = processingService.pauseProduction(factoryId, batchId, reason);

        // 3. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNumber", batch.getBatchNumber());
        result.put("status", batch.getStatus().name());
        result.put("productName", batch.getProductName());
        result.put("pauseReason", reason);
        result.put("supervisorName", batch.getSupervisorName());
        result.put("message", String.format("生产批次已暂停，批次号: %s，原因: %s",
                batch.getBatchNumber(), reason));

        log.info("生产批次暂停完成 - 批次ID: {}, 批次号: {}, 原因: {}", batch.getId(), batch.getBatchNumber(), reason);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要暂停哪个生产批次？请提供批次ID或批次号。";
            case "reason":
                return "请说明暂停生产的原因（如：设备故障、原料不足、质量问题等）。";
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
                return "暂停原因";
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
