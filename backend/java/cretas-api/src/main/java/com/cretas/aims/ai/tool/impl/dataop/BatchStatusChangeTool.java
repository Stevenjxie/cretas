package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 批次状态变更工具
 *
 * 变更生产批次状态：开始、暂停、恢复、完成、取消。
 * Intent Code: PROCESSING_BATCH_START / PAUSE / RESUME / COMPLETE / CANCEL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class BatchStatusChangeTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "batch_status_change";
    }

    @Override
    public String getDescription() {
        return "变更生产批次状态。支持的操作：start(开始生产)、pause(暂停)、resume(恢复)、complete(完成)、cancel(取消)。" +
                "适用场景：开始生产、暂停批次、恢复生产、完成批次、取消生产。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "批次ID或批次号");
        properties.put("batchId", batchId);

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("description", "状态变更操作");
        operation.put("enum", Arrays.asList("start", "pause", "resume", "complete", "cancel"));
        properties.put("operation", operation);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "暂停/取消原因");
        properties.put("reason", reason);

        Map<String, Object> actualQuantity = new HashMap<>();
        actualQuantity.put("type", "number");
        actualQuantity.put("description", "实际产量（完成时需要）");
        properties.put("actualQuantity", actualQuantity);

        Map<String, Object> goodQuantity = new HashMap<>();
        goodQuantity.put("type", "number");
        goodQuantity.put("description", "合格品数量（完成时需要）");
        properties.put("goodQuantity", goodQuantity);

        Map<String, Object> defectQuantity = new HashMap<>();
        defectQuantity.put("type", "number");
        defectQuantity.put("description", "不良品数量（完成时可选）");
        properties.put("defectQuantity", defectQuantity);

        Map<String, Object> supervisorId = new HashMap<>();
        supervisorId.put("type", "integer");
        supervisorId.put("description", "主管ID（开始生产时可选）");
        properties.put("supervisorId", supervisorId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "operation"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "operation");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "batchId" -> "请提供批次ID或批次号。";
            case "operation" -> "请指定操作：start(开始)、pause(暂停)、resume(恢复)、complete(完成)、cancel(取消)。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchId = getString(params, "batchId");
        String operation = getString(params, "operation");
        String reason = getString(params, "reason");

        try {
            ProductionBatch result = switch (operation.toLowerCase()) {
                case "start" -> {
                    Long supervisorId = getLong(params, "supervisorId");
                    Integer supervisorIdInt = supervisorId != null ? supervisorId.intValue() : null;
                    yield processingService.startProduction(factoryId, batchId, supervisorIdInt);
                }
                case "pause" -> processingService.pauseProduction(factoryId, batchId, reason);
                case "resume" -> processingService.resumeProduction(factoryId, batchId);
                case "complete" -> {
                    BigDecimal actualQty = getBigDecimal(params, "actualQuantity");
                    BigDecimal goodQty = getBigDecimal(params, "goodQuantity");
                    BigDecimal defectQty = getBigDecimal(params, "defectQuantity");
                    yield processingService.completeProduction(factoryId, batchId, actualQty, goodQty, defectQty);
                }
                case "cancel" -> processingService.cancelProduction(factoryId, batchId, reason);
                default -> throw new IllegalArgumentException("不支持的操作类型: " + operation);
            };

            return buildSimpleResult("批次状态变更成功", result);
        } catch (Exception e) {
            log.error("批次状态变更失败: factoryId={}, batchId={}, operation={}", factoryId, batchId, operation, e);
            throw e;
        }
    }


    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
