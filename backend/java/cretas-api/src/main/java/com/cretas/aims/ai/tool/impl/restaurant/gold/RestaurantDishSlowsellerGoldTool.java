package com.cretas.aims.ai.tool.impl.restaurant.gold;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滞销/慢销菜品查询工具（Gold 层 agg_product，按销售额升序取底部 10）。
 *
 * <p>适用意图：慢销菜 / 滞销 / 卖不动的菜。
 *
 * @since 2026-06-01
 */
@Slf4j
@Component
public class RestaurantDishSlowsellerGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_dish_slowseller_gold";
    }

    @Override
    public String getDescription() {
        return "查询滞销/慢销菜品(销量垫底, gold 层)。适用: 慢销菜/滞销/卖不动的菜。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new HashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份, 如 '2026年3月' / '上月', 不传默认全部数据");

        Map<String, Object> properties = new HashMap<>();
        properties.put("month", monthProp);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    // getRequiredParameters() inherited — returns empty list.

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchTopProducts(factoryId, start, end, 10, "asc");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        Object rawStart = goldResult.get("start_month");
        Object rawEnd = goldResult.get("end_month");
        String period = (rawStart != null ? rawStart.toString() : "?")
                + " 至 " + (rawEnd != null ? rawEnd.toString() : "?");

        List<Map<String, Object>> raw =
                goldResult.get("top_products") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("top_products")
                        : Collections.emptyList();

        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("菜品", row.get("product_name"));
            entry.put("销量", row.get("qty_sold"));
            entry.put("销售额", row.get("revenue"));
            formatted.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("慢销TOP10", formatted);
        result.put("dataAvailable", true);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("top_products");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无菜品销售数据。请确认已上传含菜品销量的经营报表(商品销量报表/POS 明细)。";
    }
}
