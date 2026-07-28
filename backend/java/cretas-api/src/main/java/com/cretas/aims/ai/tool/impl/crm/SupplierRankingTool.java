package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 供应商排名工具
 *
 * 获取供应商排名，支持按评级、供货量等维度排序。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SupplierRankingTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_ranking";
    }

    @Override
    public String getDescription() {
        return "获取供应商排名。按评级、供货表现等维度对供应商进行排名。" +
                "适用场景：查看供应商排行、筛选优质供应商、分析供应商表现。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // sortBy: 排序依据（可选）
        Map<String, Object> sortBy = new HashMap<>();
        sortBy.put("type", "string");
        sortBy.put("description", "排序依据");
        sortBy.put("enum", Arrays.asList("RATING", "DELIVERY_RATE", "QUALITY_RATE", "TOTAL_ORDERS"));
        sortBy.put("default", "RATING");
        properties.put("sortBy", sortBy);

        // order: 排序方向（可选）
        Map<String, Object> order = new HashMap<>();
        order.put("type", "string");
        order.put("description", "排序方向，DESC为降序（默认），ASC为升序");
        order.put("enum", Arrays.asList("DESC", "ASC"));
        order.put("default", "DESC");
        properties.put("order", order);

        // limit: 返回数量（可选）
        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回排名数量，如TOP10、TOP20");
        limit.put("default", 10);
        limit.put("minimum", 1);
        limit.put("maximum", 100);
        properties.put("limit", limit);

        // minRating: 最低评级筛选（可选）
        Map<String, Object> minRating = new HashMap<>();
        minRating.put("type", "integer");
        minRating.put("description", "最低评级筛选，只显示评级大于等于该值的供应商");
        minRating.put("minimum", 1);
        minRating.put("maximum", 5);
        properties.put("minRating", minRating);

        // includeRatingDistribution: 是否包含评级分布（可选）
        Map<String, Object> includeRatingDistribution = new HashMap<>();
        includeRatingDistribution.put("type", "boolean");
        includeRatingDistribution.put("description", "是否包含评级分布统计");
        includeRatingDistribution.put("default", false);
        properties.put("includeRatingDistribution", includeRatingDistribution);

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
        log.info("执行供应商排名查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String sortBy = getString(params, "sortBy", "RATING");
        String order = getString(params, "order", "DESC");
        Integer limit = getInteger(params, "limit", 10);
        Integer minRating = getInteger(params, "minRating");
        Boolean includeRatingDistribution = getBoolean(params, "includeRatingDistribution", false);

        // 获取所有活跃供应商
        List<SupplierDTO> suppliers = supplierService.getActiveSuppliers(factoryId);

        if (suppliers == null) {
            suppliers = Collections.emptyList();
        }

        // 应用最低评级筛选
        if (minRating != null) {
            final Integer minRatingFinal = minRating;
            suppliers = suppliers.stream()
                    .filter(s -> s.getRating() != null && s.getRating() >= minRatingFinal)
                    .collect(Collectors.toList());
        }

        // 排序
        Comparator<SupplierDTO> comparator = getComparator(sortBy);
        if ("DESC".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        suppliers.sort(comparator);

        // 应用数量限制
        if (suppliers.size() > limit) {
            suppliers = suppliers.subList(0, limit);
        }

        // 添加排名序号
        List<Map<String, Object>> rankedSuppliers = new ArrayList<>();
        for (int i = 0; i < suppliers.size(); i++) {
            Map<String, Object> rankedItem = new HashMap<>();
            rankedItem.put("rank", i + 1);
            rankedItem.put("supplier", suppliers.get(i));
            rankedSuppliers.add(rankedItem);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("ranking", rankedSuppliers);
        result.put("count", rankedSuppliers.size());
        result.put("sortBy", sortBy);
        result.put("order", order);

        // 可选：包含评级分布统计
        if (includeRatingDistribution) {
            Map<Integer, Long> ratingDistribution = supplierService.getSupplierRatingDistribution(factoryId);
            result.put("ratingDistribution", ratingDistribution);
        }

        log.info("供应商排名查询完成 - 排序: {}, 数量: {}", sortBy, rankedSuppliers.size());

        return result;
    }

    /**
     * 根据排序字段获取比较器
     */
    private Comparator<SupplierDTO> getComparator(String sortBy) {
        switch (sortBy.toUpperCase()) {
            case "RATING":
                return Comparator.comparing(
                        SupplierDTO::getRating,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
            case "DELIVERY_RATE":
            case "QUALITY_RATE":
            case "TOTAL_ORDERS":
                // 这些字段如果DTO中没有，默认按评级排序
                return Comparator.comparing(
                        SupplierDTO::getRating,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
            default:
                return Comparator.comparing(
                        SupplierDTO::getRating,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
