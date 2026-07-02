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
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 本类经 GoldBackedRestaurantTool
 * 的 final doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...)
 * → GoldFinanceClient.fetchX(factoryId, ...), 每个 gold 查询都按 factory_id 租户隔离
 * (Python gold 层 _resolve_tenant)。审计正则无法追踪模板方法 + final 修饰, 故显式豁免;
 * 隔离实际由 factoryId 全程传递保证。
 */
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

        List<String> excludedNonDish = new ArrayList<>();
        List<Map<String, Object>> dishRows = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Object productName = row.get("product_name");
            String name = productName != null ? productName.toString() : "";
            if (isNonDishLine(name)) {
                excludedNonDish.add(name);
                continue;
            }
            dishRows.add(row);
        }

        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> row : dishRows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("菜品", row.get("product_name"));
            entry.put("销量", row.get("qty_sold"));
            entry.put("销售额", row.get("revenue"));
            formatted.add(entry);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("慢销/滞销菜品（销量垫底，").append(period).append("）：\n");
        if (!excludedNonDish.isEmpty()) {
            sb.append("已先排除非菜品行：")
                    .append(String.join("、", excludedNonDish.stream().distinct().limit(4).toList()))
                    .append("。\n");
        }
        for (int i = 0; i < formatted.size(); i++) {
            Map<String, Object> entry = formatted.get(i);
            Object qty = entry.get("销量");
            Object rev = entry.get("销售额");
            double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
            sb.append(i + 1).append(". ").append(entry.get("菜品"))
                    .append(" — 销量").append(qty != null ? qty : 0).append("份，")
                    .append("销售额").append(fmtAmt(revD));
            if (i < formatted.size() - 1) sb.append("\n");
        }

        // Build chartConfig: horizontal bar — dish name vs qty sold in 份
        List<String> chartNames = new ArrayList<>();
        List<Integer> chartVals = new ArrayList<>();
        for (Map<String, Object> entry : formatted) {
            Object name = entry.get("菜品");
            Object qty = entry.get("销量");
            int qtyI = qty instanceof Number ? ((Number) qty).intValue() : 0;
            chartNames.add(name != null ? name.toString() : "");
            chartVals.add(qtyI);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("慢销TOP10", formatted);
        result.put("excluded_non_dish_lines", excludedNonDish);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!chartNames.isEmpty()) {
            result.put("chartConfig", barChartConfig(
                    "慢销菜品 (销量/份)", chartNames, chartVals, "份"));
        }
        return result;
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("top_products");
        if (!(raw instanceof List)) return true;
        List<?> rows = (List<?>) raw;
        if (rows.isEmpty()) return true;
        for (Object item : rows) {
            if (item instanceof Map<?, ?> row) {
                Object name = row.get("product_name");
                if (name != null && !isNonDishLine(name.toString())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无菜品销售数据。请确认已上传含菜品销量的经营报表(商品销量报表/POS 明细)。";
    }

    private static boolean isNonDishLine(String name) {
        if (name == null) return true;
        String n = name.trim();
        return n.isEmpty()
                || n.contains("测试")
                || n.contains("无需餐具")
                || n.contains("餐具")
                || n.contains("打包费")
                || n.contains("配送费");
    }
}
