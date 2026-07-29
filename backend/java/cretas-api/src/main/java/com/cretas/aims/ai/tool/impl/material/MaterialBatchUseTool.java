package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原材料批次使用/消耗工具
 *
 * 用于记录原材料出库消耗，支持关联生产计划。
 * 执行后会自动更新批次剩余数量，并记录使用历史。
 *
 * 业务规则：
 * 1. 批次必须存在且状态为可用
 * 2. 消耗数量不能超过剩余可用数量
 * 3. 消耗后剩余数量为0时，批次状态自动变为USED_UP
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialBatchUseTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_use";
    }

    @Override
    public String getDescription() {
        return "使用/消耗原材料，记录原料出库。需要指定批次和消耗数量。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "原材料批次ID，用于唯一标识要消耗的批次");
        properties.put("batchId", batchId);

        // quantity: 消耗数量（必需）
        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "消耗数量，必须大于0且不超过批次剩余可用数量");
        quantity.put("minimum", 0.001);
        properties.put("quantity", quantity);

        // productionPlanId: 生产计划ID（可选）
        Map<String, Object> productionPlanId = new HashMap<>();
        productionPlanId.put("type", "string");
        productionPlanId.put("description", "关联的生产计划ID，用于追溯原料使用去向");
        properties.put("productionPlanId", productionPlanId);

        // reason: 使用原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "使用/消耗原因说明，如：生产使用、样品制作、损耗等");
        reason.put("maxLength", 500);
        properties.put("reason", reason);

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
        // 1. 解析参数
        String batchId = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String productionPlanId = getString(params, "productionPlanId");
        String reason = getString(params, "reason");

        // 2. 参数验证
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("消耗数量必须大于0");
        }

        log.info("📦 执行原料消耗 - 批次: {}, 数量: {}, 生产计划: {}, 原因: {}",
                batchId, quantity, productionPlanId, reason);

        // 3. 调用服务执行消耗 (operatorId 写入 MaterialConsumption.recordedBy)
        MaterialBatchDTO updatedBatch = materialBatchService.useBatchMaterial(
                factoryId,
                batchId,
                quantity,
                productionPlanId,
                getUserId(context)
        );

        // 4. 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", updatedBatch.getId());
        result.put("batchNumber", updatedBatch.getBatchNumber());
        result.put("materialTypeId", updatedBatch.getMaterialTypeId());
        result.put("consumedQuantity", quantity);
        result.put("remainingQuantity", updatedBatch.getRemainingQuantity());
        result.put("unit", updatedBatch.getUnit());
        result.put("status", updatedBatch.getStatus());
        result.put("productionPlanId", productionPlanId);
        result.put("message", String.format("成功消耗原料批次 %s，消耗数量 %s %s，剩余 %s %s",
                updatedBatch.getBatchNumber(),
                quantity,
                updatedBatch.getUnit(),
                updatedBatch.getRemainingQuantity(),
                updatedBatch.getUnit()));

        log.info("✅ 原料消耗完成 - 批次: {}, 剩余: {}",
                updatedBatch.getBatchNumber(), updatedBatch.getRemainingQuantity());

        return result;
    }

    /**
     * 覆盖参数问题生成，提供更精确的提示
     */
    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问您要消耗哪个批次的原料？请提供批次ID或批次号。";
            case "quantity":
                return "请问需要消耗多少数量？";
            case "productionPlanId":
                return "请问这次消耗是用于哪个生产计划？（可选）";
            case "reason":
                return "请说明消耗原因。（可选）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    /**
     * 覆盖参数显示名称
     */
    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "批次ID";
            case "quantity":
                return "消耗数量";
            case "productionPlanId":
                return "生产计划ID";
            case "reason":
                return "使用原因";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /**
     * 此工具需要写操作权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 仅工厂管理员及以上可使用
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "factory_admin".equals(userRole) ||
                "warehouse_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
