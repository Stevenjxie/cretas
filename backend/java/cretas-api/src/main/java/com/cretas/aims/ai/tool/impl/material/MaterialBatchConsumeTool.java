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
 * 原材料批次消耗工具
 *
 * 用于消耗已预留的原材料，从预留数量中扣减。
 * 适用场景：生产过程中实际使用预留的原材料时调用。
 *
 * 业务规则：
 * 1. 批次必须存在且状态有效
 * 2. 消耗数量必须大于0
 * 3. 消耗数量不能超过预留数量
 * 4. 关联生产计划时会更新计划的物料消耗记录
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialBatchConsumeTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_consume";
    }

    @Override
    public String getDescription() {
        return "消耗已预留的原材料，从预留数量中扣减。" +
                "用于生产过程中实际使用预留的原材料。" +
                "需要提供批次ID和消耗数量，可选关联生产计划ID。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "要消耗的原材料批次ID");
        properties.put("batchId", batchId);

        // quantity: 消耗数量（必需）
        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "消耗数量，必须为正数，单位与批次一致（通常为kg）");
        quantity.put("minimum", 0);
        quantity.put("exclusiveMinimum", true);
        properties.put("quantity", quantity);

        // productionPlanId: 生产计划ID（可选）
        Map<String, Object> productionPlanId = new HashMap<>();
        productionPlanId.put("type", "string");
        productionPlanId.put("description", "关联的生产计划ID，用于追溯原材料使用");
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
        // 1. 提取参数
        String batchId = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String productionPlanId = getString(params, "productionPlanId");

        // 2. 参数验证
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("消耗数量必须大于0");
        }

        log.info("执行原材料消耗: factoryId={}, batchId={}, quantity={}, productionPlanId={}",
                factoryId, batchId, quantity, productionPlanId);

        // 3. 调用服务执行消耗 (operatorId 写入 MaterialConsumption.recordedBy)
        materialBatchService.consumeBatchMaterial(factoryId, batchId, quantity, productionPlanId, getUserId(context));

        // 4. 构建返回结果
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("batchId", batchId);
        resultData.put("consumedQuantity", quantity);
        if (productionPlanId != null) {
            resultData.put("productionPlanId", productionPlanId);
        }

        return buildSimpleResult(
                String.format("已成功消耗批次 %s 的原材料 %s 单位", batchId, quantity.toPlainString()),
                resultData
        );
    }

    /**
     * 覆盖参数问题提示
     */
    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "batchId", "请问您要消耗哪个批次的原材料？请提供批次ID或批次号。",
                "quantity", "请问要消耗多少数量？请提供具体数值（单位：kg）。",
                "productionPlanId", "请问这次消耗关联哪个生产计划？（可选）"
        );
        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    /**
     * 此工具需要权限验证
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 允许的角色：工厂管理员、生产主管、仓库管理员
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "production_supervisor".equals(userRole) ||
                "warehouse_manager".equals(userRole) ||
                "platform_admin".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
