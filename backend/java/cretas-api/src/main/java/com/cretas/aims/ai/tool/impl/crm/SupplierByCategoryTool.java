package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按供应类别查询供应商工具
 *
 * 根据供应的原材料类别获取供应商列表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SupplierByCategoryTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_by_category";
    }

    @Override
    public String getDescription() {
        return "按供应类别查询供应商。根据供应商提供的原材料类别筛选供应商列表。" +
                "适用场景：查找特定原料供应商、按供应品类筛选、寻找某类材料的供应商。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // category: 供应类别/原材料类型（可选但推荐）
        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description", "供应类别或原材料类型，如：肉类、蔬菜、调味料、包装材料等");
        properties.put("category", category);

        // materialType: 原材料类型别名
        Map<String, Object> materialType = new HashMap<>();
        materialType.put("type", "string");
        materialType.put("description", "原材料类型（与category同义）");
        properties.put("materialType", materialType);

        // limit: 返回数量限制（可选）
        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回结果数量限制");
        limit.put("default", 50);
        limit.put("minimum", 1);
        limit.put("maximum", 200);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("category".equals(paramName) || "materialType".equals(paramName)) {
            return "请问您要查询哪种供应类别的供应商？（如：肉类、蔬菜、调味料、包装材料等）";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("category".equals(paramName)) {
            return "供应类别";
        }
        if ("materialType".equals(paramName)) {
            return "原材料类型";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行按类别查询供应商 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 支持category或materialType参数
        String category = getString(params, "category");
        if (category == null || category.trim().isEmpty()) {
            category = getString(params, "materialType");
        }
        Integer limit = getInteger(params, "limit", 50);

        List<SupplierDTO> suppliers;
        if (category != null && !category.trim().isEmpty()) {
            suppliers = supplierService.getSuppliersByMaterialType(factoryId, category);
        } else {
            // 如果未指定类别，返回所有活跃供应商
            suppliers = supplierService.getActiveSuppliers(factoryId);
            Map<String, Object> result = new HashMap<>();
            result.put("suppliers", suppliers != null ? suppliers : Collections.emptyList());
            result.put("count", suppliers != null ? suppliers.size() : 0);
            result.put("message", "未指定供应类别，返回所有活跃供应商");
            return result;
        }

        // 应用数量限制
        if (suppliers != null && suppliers.size() > limit) {
            suppliers = suppliers.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("suppliers", suppliers != null ? suppliers : Collections.emptyList());
        result.put("count", suppliers != null ? suppliers.size() : 0);
        result.put("category", category);

        log.info("按类别查询供应商完成 - 类别: {}, 找到: {} 条记录", category,
                suppliers != null ? suppliers.size() : 0);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
