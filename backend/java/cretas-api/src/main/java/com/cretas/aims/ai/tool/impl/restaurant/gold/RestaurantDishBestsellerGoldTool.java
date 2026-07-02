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
 * 畅销菜品 Top N 查询工具（Gold 层 agg_product，按销售额降序）。
 *
 * <p>适用意图：畅销品 / 热销菜 / 卖得最好的菜。
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
public class RestaurantDishBestsellerGoldTool extends GoldBackedRestaurantTool {

    private static final String FILTER_DISH_NAME = "dish_name";
    private static final String FILTER_DISH_ID   = "dish_id";
    private static final int CANDIDATE_LIMIT = 20;
    private static final int DISPLAY_LIMIT = 5;

    @Override
    public String getToolName() {
        return "restaurant_dish_bestseller_gold";
    }

    @Override
    public String getDescription() {
        return "查询畅销菜品 Top N(按销售额, 来自 gold 层 agg_product)。适用: 畅销品/热销菜/卖得最好的菜/那道菜的趋势。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new HashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份, 如 '2026年3月' / '上月', 不传默认全部数据");

        Map<String, Object> dishNameProp = new HashMap<>();
        dishNameProp.put("type", "string");
        dishNameProp.put("description", "可选。菜品名称, 用于多轮续接后的单菜品过滤");

        Map<String, Object> dishIdProp = new HashMap<>();
        dishIdProp.put("type", "string");
        dishIdProp.put("description", "可选。菜品ID (product_id), 优先于菜品名称用于单菜品过滤");

        Map<String, Object> properties = new HashMap<>();
        properties.put("month", monthProp);
        properties.put(FILTER_DISH_NAME, dishNameProp);
        properties.put(FILTER_DISH_ID, dishIdProp);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    // getRequiredParameters() is inherited from GoldBackedRestaurantTool — returns empty list.

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        Map<String, Object> result = gold.fetchTopProducts(factoryId, start, end, CANDIDATE_LIMIT, "desc");
        if (result == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(result);
        if (params != null) {
            if (params.get(FILTER_DISH_NAME) != null) {
                copy.put(FILTER_DISH_NAME, params.get(FILTER_DISH_NAME));
            }
            if (params.get(FILTER_DISH_ID) != null) {
                copy.put(FILTER_DISH_ID, params.get(FILTER_DISH_ID));
            }
        }
        return copy;
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

        // Apply dish filter if present (for multi-turn coref)
        Object filterDishId   = goldResult.get(FILTER_DISH_ID);
        Object filterDishName = goldResult.get(FILTER_DISH_NAME);
        boolean hasDishFilter = hasText(filterDishId) || hasText(filterDishName);
        if (hasDishFilter) {
            raw = raw.stream()
                    .filter(row -> matchesDish(row, filterDishId, filterDishName))
                    .toList();
            if (raw.isEmpty()) {
                Map<String, Object> emptyResult = new LinkedHashMap<>();
                emptyResult.put("dataAvailable", false);
                emptyResult.put("message", emptyMessage());
                emptyResult.put("actionHint", "请先查询畅销菜品排行，确认要续接的菜品名称。");
                return emptyResult;
            }
        }

        List<String> excludedSupportItems = new ArrayList<>();
        List<Map<String, Object>> decisionRows = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Object productName = row.get("product_name");
            String name = productName != null ? productName.toString() : "";
            if (!hasDishFilter && isLowValueSupportItem(name)) {
                excludedSupportItems.add(name);
                continue;
            }
            decisionRows.add(row);
            if (!hasDishFilter && decisionRows.size() >= DISPLAY_LIMIT) {
                break;
            }
        }

        if (decisionRows.isEmpty()) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("dataAvailable", false);
            emptyResult.put("message", "本期畅销榜主要是米饭、餐具、饮料等低价值配套项，暂时没有足够的主菜/套餐可用于主推决策。");
            emptyResult.put("actionHint", "请补充菜品分类或配方成本，再生成主推菜品榜。");
            return emptyResult;
        }

        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> row : decisionRows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dish_id", row.get("product_id"));
            entry.put("菜品", row.get("product_name"));
            entry.put("销量", row.get("qty_sold"));
            entry.put("销售额", row.get("revenue"));
            formatted.add(entry);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("畅销主推菜品 Top").append(formatted.size())
                .append("（按销售额，").append(period).append("）：\n");
        if (!excludedSupportItems.isEmpty()) {
            sb.append("已先排除低价值配套项：")
                    .append(String.join("、", excludedSupportItems.stream().distinct().limit(5).toList()))
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

        // Build chartConfig: horizontal bar — dish name vs sales amount in 万元
        List<String> chartNames = new ArrayList<>();
        List<Double> chartVals = new ArrayList<>();
        for (Map<String, Object> entry : formatted) {
            Object name = entry.get("菜品");
            Object rev = entry.get("销售额");
            double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
            chartNames.add(name != null ? name.toString() : "");
            chartVals.add(toWan(revD));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("畅销TOP5", formatted);
        result.put("excluded_support_items", excludedSupportItems);
        if (!formatted.isEmpty()) {
            result.put("top_dish", formatted.get(0));
        }
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!chartNames.isEmpty()) {
            result.put("chartConfig", barChartConfig(
                    "畅销主推菜品 Top5 (销售额/万元)", chartNames, chartVals, "万元"));
        }
        return result;
    }

    private static boolean matchesDish(Map<String, Object> row, Object dishId, Object dishName) {
        if (hasText(dishId)) {
            Object rowId = row.get("product_id");
            return rowId != null && rowId.toString().equals(dishId.toString());
        }
        if (hasText(dishName)) {
            Object rowName = row.get("product_name");
            return rowName != null && rowName.toString().equals(dishName.toString());
        }
        return true;
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    private static boolean isLowValueSupportItem(String name) {
        if (name == null) return true;
        String n = name.trim();
        if (n.isEmpty()) return true;
        if (n.contains("套餐")) return false;
        return n.contains("米饭")
                || n.contains("白饭")
                || n.contains("无需餐具")
                || n.contains("餐具")
                || n.contains("打包费")
                || n.contains("配送费")
                || n.contains("矿泉水")
                || n.contains("可乐")
                || n.contains("雪碧")
                || n.contains("王老吉")
                || n.contains("饮料")
                || n.contains("测试");
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
