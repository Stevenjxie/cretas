package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 生产批次完成工具
 *
 * 完成生产批次，将状态从 IN_PROGRESS 更改为 COMPLETED。
 * 需要记录实际产量、良品数量和不良品数量，系统会自动计算良品率和效率。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchCompleteTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_complete";
    }

    @Override
    public String getDescription() {
        return "完成生产批次。记录实际产量、良品数量、不良品数量，将批次状态改为「已完成」。" +
                "系统会自动计算良品率、效率等指标。适用场景：生产结束、产量录入、完工确认。";
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

        // actualQuantity: 实际产量（必需）
        Map<String, Object> actualQuantity = new HashMap<>();
        actualQuantity.put("type", "number");
        actualQuantity.put("description", "实际生产总数量");
        actualQuantity.put("minimum", 0);
        properties.put("actualQuantity", actualQuantity);

        // goodQuantity: 良品数量（必需）
        Map<String, Object> goodQuantity = new HashMap<>();
        goodQuantity.put("type", "number");
        goodQuantity.put("description", "良品数量（合格产品）");
        goodQuantity.put("minimum", 0);
        properties.put("goodQuantity", goodQuantity);

        // defectQuantity: 不良品数量（必需）
        Map<String, Object> defectQuantity = new HashMap<>();
        defectQuantity.put("type", "number");
        defectQuantity.put("description", "不良品数量（不合格产品）");
        defectQuantity.put("minimum", 0);
        properties.put("defectQuantity", defectQuantity);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "actualQuantity", "goodQuantity", "defectQuantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "actualQuantity", "goodQuantity", "defectQuantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行完成生产批次 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String batchId = getString(params, "batchId");
        BigDecimal actualQuantity = getBigDecimal(params, "actualQuantity");
        BigDecimal goodQuantity = getBigDecimal(params, "goodQuantity");
        BigDecimal defectQuantity = getBigDecimal(params, "defectQuantity");

        // 2. 数据校验
        if (actualQuantity.compareTo(goodQuantity.add(defectQuantity)) != 0) {
            log.warn("产量数据不一致：实际产量 {} != 良品 {} + 不良品 {}",
                    actualQuantity, goodQuantity, defectQuantity);
            // 允许继续执行，但记录警告
        }

        // 3. 调用服务完成生产
        ProductionBatch batch = processingService.completeProduction(
                factoryId, batchId, actualQuantity, goodQuantity, defectQuantity);

        // 4. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchNumber", batch.getBatchNumber());
        result.put("status", batch.getStatus().name());
        result.put("productName", batch.getProductName());
        result.put("plannedQuantity", batch.getPlannedQuantity());
        result.put("actualQuantity", batch.getActualQuantity());
        result.put("goodQuantity", batch.getGoodQuantity());
        result.put("defectQuantity", batch.getDefectQuantity());
        result.put("unit", batch.getUnit());
        result.put("yieldRate", batch.getYieldRate());
        result.put("efficiency", batch.getEfficiency());
        result.put("startTime", batch.getStartTime() != null ? batch.getStartTime().toString() : null);
        result.put("endTime", batch.getEndTime() != null ? batch.getEndTime().toString() : null);
        result.put("workDurationMinutes", batch.getWorkDurationMinutes());
        result.put("message", String.format(
                "生产批次已完成，批次号: %s，实际产量: %s %s，良品率: %s%%",
                batch.getBatchNumber(),
                batch.getActualQuantity(),
                batch.getUnit(),
                batch.getYieldRate() != null ? batch.getYieldRate() : "N/A"));

        log.info("生产批次完成 - 批次ID: {}, 批次号: {}, 良品率: {}%",
                batch.getId(), batch.getBatchNumber(), batch.getYieldRate());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要完成哪个生产批次？请提供批次ID或批次号。";
            case "actualQuantity":
                return "请问实际生产了多少数量？";
            case "goodQuantity":
                return "请问良品（合格产品）数量是多少？";
            case "defectQuantity":
                return "请问不良品（不合格产品）数量是多少？";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            case "actualQuantity":
                return "实际产量";
            case "goodQuantity":
                return "良品数量";
            case "defectQuantity":
                return "不良品数量";
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
