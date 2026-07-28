package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 客户购买历史查询工具
 *
 * 获取指定客户的购买历史记录。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class CustomerPurchaseHistoryTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_purchase_history";
    }

    @Override
    public String getDescription() {
        return "查询客户购买历史。获取指定客户的历史购买记录，包括购买时间、产品、数量等信息。" +
                "适用场景：查看客户订单历史、分析客户购买行为、了解客户消费情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // customerId: 客户ID（必需）
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID，用于指定要查询的客户");
        properties.put("customerId", customerId);

        // limit: 返回数量限制（可选）
        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回记录数量限制");
        limit.put("default", 50);
        limit.put("minimum", 1);
        limit.put("maximum", 200);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("customerId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("customerId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("customerId".equals(paramName)) {
            return "请问您要查询哪位客户的购买历史？请提供客户ID或客户名称。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("customerId".equals(paramName)) {
            return "客户ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行客户购买历史查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String customerId = getString(params, "customerId");
        Integer limit = getInteger(params, "limit", 50);

        List<Map<String, Object>> purchaseHistory = customerService.getCustomerPurchaseHistory(factoryId, customerId);

        // 应用数量限制
        if (purchaseHistory != null && purchaseHistory.size() > limit) {
            purchaseHistory = purchaseHistory.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("customerId", customerId);
        result.put("purchaseHistory", purchaseHistory != null ? purchaseHistory : Collections.emptyList());
        result.put("count", purchaseHistory != null ? purchaseHistory.size() : 0);

        log.info("客户购买历史查询完成 - 客户ID: {}, 找到: {} 条记录", customerId,
                purchaseHistory != null ? purchaseHistory.size() : 0);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
