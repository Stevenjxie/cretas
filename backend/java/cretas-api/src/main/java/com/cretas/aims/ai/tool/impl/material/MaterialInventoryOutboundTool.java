package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 库存出库操作工具
 *
 * 执行原材料出库操作，支持生产领用、调拨等出库场景。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialInventoryOutboundTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_inventory_outbound";
    }

    @Override
    public String getDescription() {
        return "执行原材料出库操作。" +
                "需要指定原料名称、出库数量和出库原因。" +
                "适用场景：生产领用出库、库间调拨、其他出库。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> materialName = new HashMap<>();
        materialName.put("type", "string");
        materialName.put("description", "原料名称");
        properties.put("materialName", materialName);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "出库数量");
        quantity.put("minimum", 0);
        quantity.put("exclusiveMinimum", true);
        properties.put("quantity", quantity);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "出库原因");
        reason.put("enum", Arrays.asList("生产领用", "调拨", "其他"));
        properties.put("reason", reason);

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "指定出库的批次ID（可选，不指定则按FIFO自动选择）");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("materialName", "quantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("materialName", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String materialName = getString(params, "materialName");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        String reason = getString(params, "reason", "生产领用");
        String batchId = getString(params, "batchId");

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("出库数量必须大于0");
        }

        log.info("出库操作: factoryId={}, materialName={}, quantity={}, reason={}, batchId={}",
                factoryId, materialName, quantity, reason, batchId);

        Map<String, Object> result = new HashMap<>();
        result.put("materialName", materialName);
        result.put("quantity", quantity);
        result.put("reason", reason);
        result.put("operation", "OUTBOUND");

        if (batchId != null) {
            // 指定批次出库
            materialBatchService.useBatchQuantity(factoryId, batchId, quantity);
            result.put("batchId", batchId);
            result.put("status", "COMPLETED");
            result.put("message", String.format("出库成功：%s %s 单位（批次: %s，原因: %s）",
                    materialName, quantity, batchId, reason));
        } else {
            // 未指定批次，返回提示信息
            result.put("status", "PENDING");
            result.put("message", String.format("出库请求已记录：%s %s 单位（原因: %s）。" +
                    "建议指定批次ID或使用FIFO推荐工具选择批次后再执行出库。",
                    materialName, quantity, reason));
        }

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("materialName", "请问要出库哪种原料？");
        questions.put("quantity", "请问出库数量是多少？");
        questions.put("reason", "请问出库原因是什么？（生产领用/调拨/其他）");
        questions.put("batchId", "请问是否指定出库批次？如有请提供批次ID。");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("materialName", "原料名称");
        displayNames.put("quantity", "出库数量");
        displayNames.put("reason", "出库原因");
        displayNames.put("batchId", "批次ID");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "warehouse_manager".equals(userRole) ||
                "workshop_supervisor".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
