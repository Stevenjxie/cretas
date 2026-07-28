package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 低库存预警工具
 *
 * 查询当前工厂所有低于安全库存水平的原材料。
 * 返回包含原材料类型、当前库存量、安全库存量、缺口数量等关键信息。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialLowStockAlertTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_low_stock_alert";
    }

    @Override
    public String getDescription() {
        return "查询低库存预警。返回所有低于安全库存水平的原材料列表，包括原材料名称、" +
                "当前库存量、安全库存量、缺口数量等。适用于采购计划制定、库存补货提醒、" +
                "供应链管理等场景。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        // 无参数
        Map<String, Object> properties = new HashMap<>();
        schema.put("properties", properties);

        // 无必需字段
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // 无必需参数
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询低库存预警: factoryId={}", factoryId);

        // 调用服务获取低库存预警列表
        List<Map<String, Object>> lowStockWarnings = materialBatchService.getLowStockWarnings(factoryId);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("total", lowStockWarnings.size());

        if (lowStockWarnings.isEmpty()) {
            result.put("message", "当前没有低库存预警，所有原材料库存充足");
            result.put("warnings", Collections.emptyList());
        } else {
            result.put("message", String.format("共有 %d 种原材料库存低于安全水平", lowStockWarnings.size()));
            // 格式化预警数据
            List<Map<String, Object>> formattedWarnings = formatWarnings(lowStockWarnings);
            result.put("warnings", formattedWarnings);
        }

        return result;
    }

    /**
     * 格式化预警数据（确保字段顺序和格式一致）
     */
    private List<Map<String, Object>> formatWarnings(List<Map<String, Object>> warnings) {
        List<Map<String, Object>> formatted = new ArrayList<>();

        for (Map<String, Object> warning : warnings) {
            Map<String, Object> item = new LinkedHashMap<>();

            // 原材料信息
            item.put("materialTypeId", warning.get("materialTypeId"));
            item.put("materialName", warning.get("materialName"));
            item.put("materialCode", warning.get("materialCode"));
            item.put("category", warning.get("category"));

            // 库存信息
            item.put("currentStock", warning.get("currentStock"));
            item.put("safetyStock", warning.get("safetyStock"));
            item.put("unit", warning.get("unit"));

            // 缺口信息
            Object currentStock = warning.get("currentStock");
            Object safetyStock = warning.get("safetyStock");
            if (currentStock instanceof Number && safetyStock instanceof Number) {
                double current = ((Number) currentStock).doubleValue();
                double safety = ((Number) safetyStock).doubleValue();
                double gap = safety - current;
                item.put("gap", gap > 0 ? gap : 0);
                // 计算库存占比
                if (safety > 0) {
                    double ratio = (current / safety) * 100;
                    item.put("stockRatio", String.format("%.1f%%", ratio));
                }
            }

            // 预警级别（可选，如果服务返回了的话）
            if (warning.containsKey("warningLevel")) {
                item.put("warningLevel", warning.get("warningLevel"));
            }

            // 供应商信息（如果有）
            if (warning.containsKey("preferredSupplier")) {
                item.put("preferredSupplier", warning.get("preferredSupplier"));
            }

            formatted.add(item);
        }

        return formatted;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
