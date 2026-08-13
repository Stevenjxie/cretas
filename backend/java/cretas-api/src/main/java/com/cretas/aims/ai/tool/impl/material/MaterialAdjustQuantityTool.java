package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 原材料批次库存数量调整工具
 *
 * 用于调整原材料批次的库存数量，支持增加或减少操作。
 * 需要管理员权限才能执行此操作。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialAdjustQuantityTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_adjust_quantity";
    }

    @Override
    public String getDescription() {
        return "调整原材料批次的库存数量。" +
                "可用于库存盘点调整、损耗记录、入库补录等场景。" +
                "需要提供批次ID、调整后的数量以及调整原因。" +
                "此操作需要管理员权限。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（必需）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "原材料批次的唯一标识符（批次ID或批次号）");
        properties.put("batchId", batchId);

        // quantity: 调整后的数量或调整量（必需）
        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "调整后的库存数量，必须是非负数。单位与批次原有单位一致");
        quantity.put("minimum", 0);
        properties.put("quantity", quantity);

        // reason: 调整原因（必需）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "库存调整的原因说明，如：盘点调整、损耗、入库补录等");
        reason.put("maxLength", 500);
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "quantity", "reason"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "quantity", "reason");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 获取参数
        String batchId = getString(params, "batchId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String reason = getString(params, "reason");
        Long userId = getUserId(context);

        // 验证数量非负
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("调整数量不能为负数");
        }

        log.info("调整批次库存: factoryId={}, batchId={}, quantity={}, reason={}, userId={}",
                factoryId, batchId, quantity, reason, userId);

        // 🔴 2026-08-13: previousQuantity 必须在**任何写入之前**抓。原实现取的是
        // updatedBatch.getReceiptQuantity() —— 那是调整**之后**的【入库总量】,
        // 既不是"之前"也不是同一个量纲(currentQuantity 是剩余量)。于是 AI 会拿一对
        // 都属于调整后状态的数去跟操作员确认: 实测把剩余 53 调成 3, 它报的是
        // 「从 5 调整为 3」—— 看着自洽, 全错, 操作员据此无法发现调错了。
        BigDecimal previousQuantity = null;
        try {
            MaterialBatchDTO before = materialBatchService.getMaterialBatchById(factoryId, batchId);
            previousQuantity = before != null ? before.getCurrentQuantity() : null;
        } catch (Exception e) {
            // 读不到就如实留空, 不拿别的数顶替 —— 编一个"之前"比没有更糟。
            log.warn("调整前读取批次失败, previousQuantity 置空: batchId={}, err={}", batchId, e.toString());
        }

        // 调用服务层执行调整
        MaterialBatchDTO updatedBatch = materialBatchService.adjustBatchQuantity(
                factoryId, batchId, quantity, reason, userId);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", updatedBatch.getId());
        result.put("batchNumber", updatedBatch.getBatchNumber());
        result.put("previousQuantity", previousQuantity);
        result.put("currentQuantity", updatedBatch.getCurrentQuantity());
        result.put("unit", updatedBatch.getUnit());
        result.put("adjustmentReason", reason);
        result.put("adjustedBy", userId);
        result.put("message", String.format("批次 %s 库存已调整为 %s %s",
                updatedBatch.getBatchNumber(),
                updatedBatch.getCurrentQuantity(),
                updatedBatch.getUnit() != null ? updatedBatch.getUnit() : ""));

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "batchId", "请问您要调整哪个批次的库存？请提供批次ID或批次号。",
                "quantity", "请问调整后的库存数量是多少？",
                "reason", "请说明调整库存的原因（如：盘点调整、损耗、入库补录等）。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "batchId", "批次ID",
                "quantity", "调整数量",
                "reason", "调整原因"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /**
     * 此工具需要管理员权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 仅管理员角色可使用
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "warehouse_admin".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
