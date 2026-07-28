package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 活跃客户查询工具
 *
 * 获取所有活跃状态的客户列表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class CustomerActiveTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_active";
    }

    @Override
    public String getDescription() {
        return "获取所有活跃客户列表。返回当前处于活跃状态的所有客户。" +
                "适用场景：查看活跃客户、获取可合作客户、筛选有效客户。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

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
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行活跃客户查询 - 工厂ID: {}", factoryId);

        Integer limit = getInteger(params, "limit", 50);

        List<CustomerDTO> activeCustomers = customerService.getActiveCustomers(factoryId);

        // 应用数量限制
        if (activeCustomers != null && activeCustomers.size() > limit) {
            activeCustomers = activeCustomers.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("customers", activeCustomers != null ? activeCustomers : Collections.emptyList());
        result.put("count", activeCustomers != null ? activeCustomers.size() : 0);
        result.put("status", "ACTIVE");

        log.info("活跃客户查询完成 - 找到: {} 条记录",
                activeCustomers != null ? activeCustomers.size() : 0);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
