package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 客户搜索工具
 *
 * 根据关键词搜索客户，支持按名称模糊匹配。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class CustomerSearchTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_search";
    }

    @Override
    public String getDescription() {
        return "按关键词搜索客户。支持按客户名称模糊匹配，返回匹配的客户列表。" +
                "适用场景：查找特定客户、按名称检索客户、模糊查询客户。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // keyword: 搜索关键词（可选但推荐）
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "搜索关键词，用于匹配客户名称");
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
        log.info("执行客户搜索 - 工厂ID: {}, 参数: {}", factoryId, params);

        String keyword = getString(params, "keyword", "");
        Integer limit = getInteger(params, "limit", 20);

        List<CustomerDTO> customers = customerService.searchCustomersByName(factoryId, keyword);

        // 应用数量限制
        if (customers != null && customers.size() > limit) {
            customers = customers.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("customers", customers != null ? customers : Collections.emptyList());
        result.put("count", customers != null ? customers.size() : 0);
        result.put("keyword", keyword);

        log.info("客户搜索完成 - 关键词: {}, 找到: {} 条记录", keyword,
                customers != null ? customers.size() : 0);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
