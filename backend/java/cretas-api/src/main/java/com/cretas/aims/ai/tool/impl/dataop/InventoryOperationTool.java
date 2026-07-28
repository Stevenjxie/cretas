package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 库存操作工具
 *
 * 处理库存出库和清零操作。
 * Intent Code: INVENTORY_CLEAR / INVENTORY_OUTBOUND / WAREHOUSE_OUTBOUND
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class InventoryOperationTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "inventory_operation";
    }

    @Override
    public String getDescription() {
        return "执行库存操作，包括出库和库存清零。出库需要指定批次和数量，清零需要确认。" +
                "适用场景：出库操作、库存清零、批次出库。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "批次ID");
        properties.put("batchId", batchId);

        Map<String, Object> operationType = new HashMap<>();
        operationType.put("type", "string");
        operationType.put("description", "操作类型");
        operationType.put("enum", Arrays.asList("OUTBOUND", "CLEAR"));
        properties.put("operationType", operationType);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "number");
        quantity.put("description", "出库数量（出库时需要）");
        properties.put("quantity", quantity);

        Map<String, Object> confirmed = new HashMap<>();
        confirmed.put("type", "boolean");
        confirmed.put("description", "是否已确认（清零时需要）");
        properties.put("confirmed", confirmed);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchId", "operationType"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchId", "operationType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchId = getString(params, "batchId");
        String operationType = getString(params, "operationType");

        try {
            if ("OUTBOUND".equalsIgnoreCase(operationType)) {
                Object qtyObj = params.get("quantity");
                if (qtyObj == null) {
                    return buildSimpleResult("缺少出库数量", Map.of("error", "出库操作需要提供 quantity 参数"));
                }
                BigDecimal quantity;
                if (qtyObj instanceof BigDecimal) {
                    quantity = (BigDecimal) qtyObj;
                } else if (qtyObj instanceof Number) {
                    quantity = BigDecimal.valueOf(((Number) qtyObj).doubleValue());
                } else {
                    quantity = new BigDecimal(qtyObj.toString());
                }

                materialBatchService.useBatchQuantity(factoryId, batchId, quantity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("batchId", batchId);
                result.put("operation", "OUTBOUND");
                result.put("quantity", quantity);
                result.put("success", true);
                return buildSimpleResult("出库操作成功", result);

            } else if ("CLEAR".equalsIgnoreCase(operationType)) {
                Boolean confirmed = (Boolean) params.get("confirmed");
                if (!Boolean.TRUE.equals(confirmed)) {
                    return buildSimpleResult("清零操作需要确认", Map.of(
                            "message", "库存清零是高危操作，请传入 confirmed=true 确认执行",
                            "batchId", batchId));
                }

                // Adjust quantity to zero with adjustment of -current_quantity
                MaterialBatchDTO current = materialBatchService.getMaterialBatchById(factoryId, batchId);
                if (current == null) {
                    return buildSimpleResult("未找到批次", Map.of("batchId", batchId, "found", false));
                }

                BigDecimal currentQty = current.getCurrentQuantity();
                if (currentQty != null && currentQty.compareTo(BigDecimal.ZERO) > 0) {
                    // T-R5-4: CLEAR is explicit DELTA math (current + (-current) = 0),
                    // so call the renamed delta overload.
                    materialBatchService.applyBatchQuantityDelta(factoryId, batchId,
                            currentQty.negate(), "AI工具清零操作");
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("batchId", batchId);
                result.put("operation", "CLEAR");
                result.put("previousQuantity", currentQty);
                result.put("success", true);
                return buildSimpleResult("库存清零成功", result);

            } else {
                return buildSimpleResult("不支持的操作类型", Map.of("operationType", operationType,
                        "supported", Arrays.asList("OUTBOUND", "CLEAR")));
            }
        } catch (Exception e) {
            log.error("库存操作失败: factoryId={}, batchId={}, operationType={}", factoryId, batchId, operationType, e);
            throw e;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
