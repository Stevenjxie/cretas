package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原材料批次释放预留工具
 *
 * 用于释放之前预留的原材料数量，将其归还到可用库存。
 * 释放操作通常在生产计划取消或变更时执行。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialBatchReleaseTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_release";
    }

    @Override
    public String getDescription() {
        return "释放预留的原材料批次数量。" +
                "释放后，该数量将归还到可用库存中。" +
                "可指定生产计划ID以释放特定计划的预留。" +
                "适用场景：生产计划取消、订单变更、预留调整等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "原材料批次ID");
        properties.put("batchId", batchId);

        // quantity: 释放数量（必需）
        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "释放数量，必须大于0且不超过已预留数量");
        quantity.put("minimum", 0);
        quantity.put("exclusiveMinimum", true);
        properties.put("quantity", quantity);

        // productionPlanId: 生产计划ID（可选）
        Map<String, Object> productionPlanId = new HashMap<>();
        productionPlanId.put("type", "string");
        productionPlanId.put("description", "关联的生产计划ID，用于释放特定计划的预留");
        properties.put("productionPlanId", productionPlanId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "quantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 获取参数
        String batchId = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String productionPlanId = getString(params, "productionPlanId");

        // 2. 参数验证
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("释放数量必须大于0");
        }

        log.info("📦 开始释放预留材料: factoryId={}, batchId={}, quantity={}, productionPlanId={}",
                factoryId, batchId, quantity, productionPlanId);

        // 3. 调用服务释放预留
        materialBatchService.releaseBatchReservation(factoryId, batchId, quantity, productionPlanId);

        // 4. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("releasedQuantity", quantity);
        result.put("productionPlanId", productionPlanId);
        result.put("message", String.format("成功释放批次 %s 的 %s 单位预留材料", batchId, quantity));

        log.info("✅ 预留材料释放成功: batchId={}, quantity={}", batchId, quantity);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("batchId", "请问您要释放哪个批次的预留材料？请提供批次ID。");
        questions.put("quantity", "请问您要释放多少数量？");
        questions.put("productionPlanId", "请问是否针对某个特定生产计划释放？如有请提供计划ID。");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("batchId", "批次ID");
        displayNames.put("quantity", "释放数量");
        displayNames.put("productionPlanId", "生产计划ID");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
