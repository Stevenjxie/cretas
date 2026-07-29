package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 客户统计工具
 *
 * 获取客户统计信息，支持单个客户统计或整体统计。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class CustomerStatsTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    @Override
    public String getToolName() {
        return "customer_stats";
    }

    @Override
    public String getDescription() {
        return "获取客户统计信息。可查询单个客户的详细统计或获取整体客户统计数据。" +
                "适用场景：分析客户数据、查看客户概况、获取客户报表、了解客户分布。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // customerId: 客户ID（可选，不指定则返回整体统计）
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID，不指定则返回整体客户统计");
        properties.put("customerId", customerId);

        // includeRatingDistribution: 是否包含评级分布（可选）
        Map<String, Object> includeRatingDistribution = new HashMap<>();
        includeRatingDistribution.put("type", "boolean");
        includeRatingDistribution.put("description", "是否包含客户评级分布统计");
        includeRatingDistribution.put("default", true);
        properties.put("includeRatingDistribution", includeRatingDistribution);

        // includeTypeDistribution: 是否包含类型分布（可选）
        Map<String, Object> includeTypeDistribution = new HashMap<>();
        includeTypeDistribution.put("type", "boolean");
        includeTypeDistribution.put("description", "是否包含客户类型分布统计");
        includeTypeDistribution.put("default", true);
        properties.put("includeTypeDistribution", includeTypeDistribution);

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
        log.info("执行客户统计查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String customerId = getString(params, "customerId");
        Boolean includeRatingDistribution = getBoolean(params, "includeRatingDistribution", true);
        Boolean includeTypeDistribution = getBoolean(params, "includeTypeDistribution", true);

        Map<String, Object> result = new HashMap<>();

        if (customerId != null && !customerId.trim().isEmpty()) {
            // 查询单个客户统计
            Map<String, Object> customerStats = customerService.getCustomerStatistics(factoryId, customerId);
            result.put("customerId", customerId);
            result.put("customerStatistics", customerStats);
            result.put("type", "INDIVIDUAL");
            log.info("客户统计查询完成 - 客户ID: {}", customerId);
        } else {
            // 查询整体统计
            Map<String, Object> overallStats = customerService.getOverallCustomerStatistics(factoryId);
            result.put("overallStatistics", overallStats);
            result.put("type", "OVERALL");

            if (includeRatingDistribution) {
                Map<Integer, Long> ratingDistribution = customerService.getCustomerRatingDistribution(factoryId);
                result.put("ratingDistribution", ratingDistribution);
            }

            if (includeTypeDistribution) {
                Map<String, Long> typeDistribution = customerService.getCustomerTypeDistribution(factoryId);
                result.put("typeDistribution", typeDistribution);
            }

            log.info("整体客户统计查询完成");
        }

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
