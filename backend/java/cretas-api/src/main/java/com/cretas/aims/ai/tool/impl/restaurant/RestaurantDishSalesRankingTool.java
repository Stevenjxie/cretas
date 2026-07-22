package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜品销量排行工具 (TOP 10)
 *
 * 通过 sales_order_items JOIN product_types，按销售数量汇总排名。
 * 对应意图: RESTAURANT_DISH_SALES_RANKING
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class RestaurantDishSalesRankingTool extends AbstractBusinessTool {

    @Autowired
    private TieredIntentDelegate tieredDelegate;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;

    @Override
    public String getToolName() {
        return "restaurant_dish_sales_ranking";
    }

    @Override
    public String getDescription() {
        return "查看菜品销量排行（TOP 10）。默认统计近30天，可指定日期范围。" +
                "适用场景：了解哪些菜品最畅销、销售排名分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd，默认30天前");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd，默认今天");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

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
        Map<String, Object> delegated = tieredDelegate.tryDelegate(factoryId, params, context, getToolName());
        if (delegated != null) {
            return delegated;
        }
        log.info("执行菜品销量排行查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        if (startDateStr != null) startDate = LocalDate.parse(startDateStr);
        if (endDateStr != null) endDate = LocalDate.parse(endDateStr);

        var orders = salesOrderRepository.findByFactoryIdAndDateRange(factoryId, startDate, endDate);

        if (orders.isEmpty()) {
            return buildSimpleResult(
                    String.format("近 %d 天（%s 至 %s）暂无销售记录，无法生成排行。",
                            java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate), startDate, endDate), null);
        }

        // 汇总每道菜的销售数量
        Map<String, double[]> accumulator = new HashMap<>(); // [totalQty, totalAmount]
        Map<String, String> nameMap = new HashMap<>();
        for (var order : orders) {
            var items = salesOrderItemRepository.findBySalesOrderId(order.getId());
            for (var item : items) {
                if (item.getProductTypeId() == null) continue;
                accumulator.computeIfAbsent(item.getProductTypeId(), k -> new double[2]);
                double[] acc = accumulator.get(item.getProductTypeId());
                if (item.getQuantity() != null) acc[0] += item.getQuantity().doubleValue();
                if (item.getQuantity() != null && item.getUnitPrice() != null)
                    acc[1] += item.getQuantity().multiply(item.getUnitPrice()).doubleValue();
                if (!nameMap.containsKey(item.getProductTypeId()) && item.getProductName() != null)
                    nameMap.put(item.getProductTypeId(), item.getProductName());
            }
        }

        List<Map<String, Object>> ranking = accumulator.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[0]).reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("菜品名称", nameMap.getOrDefault(e.getKey(), e.getKey()));
                    row.put("销售数量", String.format("%.1f", e.getValue()[0]));
                    row.put("销售金额", String.format("¥%.2f", e.getValue()[1]));
                    return row;
                }).collect(Collectors.toList());

        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).put("排名", i + 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", startDate + " 至 " + endDate);
        result.put("ranking", ranking);

        log.info("菜品销量排行查询完成 - TOP {}", ranking.size());
        return result;
    }
}
