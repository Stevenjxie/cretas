package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 供应商搜索工具
 *
 * 根据关键词搜索供应商，支持按名称模糊匹配。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SupplierSearchTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_search";
    }

    @Override
    public String getDescription() {
        return "按关键词搜索供应商。支持按供应商名称模糊匹配，返回匹配的供应商列表。" +
                "适用场景：查找特定供应商、按名称检索供应商、模糊查询供应商。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // keyword: 搜索关键词（可选但推荐）
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "搜索关键词，用于匹配供应商名称");
        properties.put("keyword", keyword);

        // limit: 返回数量限制（可选，默认20）
        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回结果数量限制");
        limit.put("default", 20);
        limit.put("minimum", 1);
        limit.put("maximum", 100);
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
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行供应商搜索 - 工厂ID: {}, 参数: {}", factoryId, params);

        String keyword = getString(params, "keyword", "");
        Integer limit = getInteger(params, "limit", 20);

        List<SupplierDTO> suppliers = supplierService.searchSuppliersByName(factoryId, keyword);

        // 应用数量限制
        if (suppliers != null && suppliers.size() > limit) {
            suppliers = suppliers.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("suppliers", suppliers != null ? suppliers : Collections.emptyList());
        result.put("count", suppliers != null ? suppliers.size() : 0);
        result.put("keyword", keyword);

        log.info("供应商搜索完成 - 关键词: {}, 找到: {} 条记录", keyword,
                suppliers != null ? suppliers.size() : 0);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
