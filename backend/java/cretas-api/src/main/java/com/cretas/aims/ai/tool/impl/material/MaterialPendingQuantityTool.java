package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 订单缺料数量查询工具
 *
 * 查询当前订单所需但库存不足的原材料情况。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class MaterialPendingQuantityTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_pending_quantity";
    }

    @Override
    public String getDescription() {
        return "查询订单缺料情况。" +
                "返回库存不足的原材料列表和当前库存量。" +
                "适用场景：订单排产前检查物料、采购计划制定、缺料预警。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询订单缺料情况: factoryId={}", factoryId);

        List<Map<String, Object>> lowStock = materialBatchService.getLowStockWarnings(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("lowStockCount", lowStock.size());
        result.put("lowStockItems", lowStock.size() > 10 ? lowStock.subList(0, 10) : lowStock);

        StringBuilder sb = new StringBuilder();
        sb.append("订单缺料情况查询：");
        sb.append("低库存原料: ").append(lowStock.size()).append("种");
        if (lowStock.isEmpty()) {
            sb.append("，当前库存充足，无缺料情况");
        }
        result.put("message", sb.toString());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
