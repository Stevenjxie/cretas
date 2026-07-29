package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按类型查询客户工具
 *
 * 根据客户类型获取客户列表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class CustomerByTypeTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_by_type";
    }

    @Override
    public String getDescription() {
        return "按客户类型查询客户列表。根据指定的客户类型筛选客户。" +
                "适用场景：查看特定类型客户、按类别分类查询、获取某类客户清单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // type: 客户类型（可选但推荐）
        Map<String, Object> type = new HashMap<>();
        type.put("type", "string");
        type.put("description", "客户类型，如：企业客户、个人客户、经销商、零售商等");
        type.put("enum", Arrays.asList(
                "ENTERPRISE",      // 企业客户
                "INDIVIDUAL",      // 个人客户
                "DEALER",          // 经销商
                "RETAILER",        // 零售商
                "WHOLESALER",      // 批发商
                "RESTAURANT",      // 餐饮客户
                "SUPERMARKET",     // 超市
                "OTHER"            // 其他
        ));
        properties.put("type", type);

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
        if ("type".equals(paramName)) {
            return "请问您要查询哪种类型的客户？（如：企业客户、个人客户、经销商、零售商等）";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("type".equals(paramName)) {
            return "客户类型";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行按类型查询客户 - 工厂ID: {}, 参数: {}", factoryId, params);

        String type = getString(params, "type");
        Integer limit = getInteger(params, "limit", 50);

        List<CustomerDTO> customers;
        if (type != null && !type.trim().isEmpty()) {
            customers = customerService.getCustomersByType(factoryId, type);
        } else {
            // 如果未指定类型，返回客户类型分布
            Map<String, Long> distribution = customerService.getCustomerTypeDistribution(factoryId);
            Map<String, Object> result = new HashMap<>();
            result.put("typeDistribution", distribution);
            result.put("message", "未指定客户类型，返回客户类型分布统计");
            return result;
        }

        // 应用数量限制
        if (customers != null && customers.size() > limit) {
            customers = customers.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("customers", customers != null ? customers : Collections.emptyList());
        result.put("count", customers != null ? customers.size() : 0);
        result.put("type", type);

        log.info("按类型查询客户完成 - 类型: {}, 找到: {} 条记录", type,
                customers != null ? customers.size() : 0);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
