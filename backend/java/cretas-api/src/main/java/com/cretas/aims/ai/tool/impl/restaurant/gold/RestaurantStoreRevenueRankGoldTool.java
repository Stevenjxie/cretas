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
 * 门店营收排行查询工具（Gold 层 agg_daily，按门店收入降序取 Top 5）。
 *
 * <p>适用意图：哪家店业绩最好 / 门店排名 / 最赚钱的店。
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
public class RestaurantStoreRevenueRankGoldTool extends GoldBackedRestaurantTool {

    private static final String FILTER_STORE_NAME = "store_name";
    private static final String FILTER_STORE_ID = "store_id";
    private static final String USER_INPUT = "userInput";

    @Override
    public String getToolName() {
        return "restaurant_store_revenue_rank_gold";
    }

    @Override
    public String getDescription() {
        return "门店营收排行(哪家店业绩最好, gold 层 agg_daily)。适用: 哪家店业绩最好/门店排名/最赚钱的店。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new HashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份, 如 '2026年3月' / '上月', 不传默认全部数据");

        Map<String, Object> storeNameProp = new HashMap<>();
        storeNameProp.put("type", "string");
        storeNameProp.put("description", "可选。门店名称, 用于多轮续接后的单门店过滤");

        Map<String, Object> storeIdProp = new HashMap<>();
        storeIdProp.put("type", "string");
        storeIdProp.put("description", "可选。门店ID, 优先于门店名称用于单门店过滤");

        Map<String, Object> properties = new HashMap<>();
        properties.put("month", monthProp);
        properties.put(FILTER_STORE_NAME, storeNameProp);
        properties.put(FILTER_STORE_ID, storeIdProp);

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
        Map<String, Object> result = gold.fetchFinanceSummary(factoryId, start, end, 50);
        if (result == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(result);
        if (params != null) {
            if (params.get(FILTER_STORE_NAME) != null) {
                copy.put(FILTER_STORE_NAME, params.get(FILTER_STORE_NAME));
            }
            if (params.get(FILTER_STORE_ID) != null) {
                copy.put(FILTER_STORE_ID, params.get(FILTER_STORE_ID));
            }
            if (params.get(USER_INPUT) != null) {
                copy.put(USER_INPUT, params.get(USER_INPUT));
            }
        }
        return copy;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        Object rawStart = goldResult.get("start_date");
        Object rawEnd = goldResult.get("end_date");
        String period = (rawStart != null ? rawStart.toString() : "?")
                + " 至 " + (rawEnd != null ? rawEnd.toString() : "?");

        List<Map<String, Object>> rawStores =
                goldResult.get("top_stores") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("top_stores")
                        : Collections.emptyList();

        Object filterStoreId = goldResult.get(FILTER_STORE_ID);
        Object filterStoreName = goldResult.get(FILTER_STORE_NAME);
        boolean hasStoreFilter = hasText(filterStoreId) || hasText(filterStoreName);
        if (hasStoreFilter) {
            rawStores = rawStores.stream()
                    .filter(row -> matchesStore(row, filterStoreId, filterStoreName))
                    .toList();
            if (rawStores.isEmpty()) {
                Map<String, Object> emptyResult = new LinkedHashMap<>();
                emptyResult.put("dataAvailable", false);
                emptyResult.put("message", emptyMessage());
                emptyResult.put("actionHint", "请先查询门店营收排行，确认要续接的门店名称。");
                return emptyResult;
            }
        }

        Object storeCountObj = goldResult.get("store_count");
        int storeCount = storeCountObj instanceof Number ? ((Number) storeCountObj).intValue() : rawStores.size();

        List<Map<String, Object>> storeRank = new ArrayList<>();
        for (Map<String, Object> row : rawStores) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("store_id", row.get("store_id"));
            entry.put("门店", row.get("store_name"));
            entry.put("营收", row.get("revenue"));
            entry.put("单数", row.get("bill_count"));
            java.math.BigDecimal avgTicket = deriveAvgTicket(row.get("revenue"), row.get("bill_count"));
            if (avgTicket != null) entry.put("客单价", avgTicket);
            storeRank.add(entry);
        }

        boolean directTopAnswer = isDirectTopStoreQuestion(goldResult.get(USER_INPUT));
        StringBuilder sb = new StringBuilder();
        if (directTopAnswer && !storeRank.isEmpty()) {
            Map<String, Object> top = storeRank.get(0);
            Object revenue = top.get("营收");
            Object bills = top.get("单数");
            double revenueValue = revenue instanceof Number ? ((Number) revenue).doubleValue() : 0.0;
            sb.append("第一名是").append(top.get("门店")).append("。核心依据：在 ")
                    .append(period).append(" 的 ").append(storeCount).append(" 家门店中，营收 ")
                    .append(fmtAmt(revenueValue)).append(" 最高；同期 ")
                    .append(bills != null ? bills : 0).append(" 单");
            if (top.get("客单价") != null) {
                sb.append("，客单价 ¥").append(top.get("客单价"));
            }
            sb.append("。");
        } else {
            sb.append("门店营收排行（").append(period).append("，共").append(storeCount).append("家）：\n");
            for (int i = 0; i < storeRank.size(); i++) {
                Map<String, Object> entry = storeRank.get(i);
                Object rev = entry.get("营收");
                Object bills = entry.get("单数");
                double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
                sb.append(i + 1).append(". ").append(entry.get("门店"))
                        .append(" — 营收").append(fmtAmt(revD))
                        .append("，").append(bills != null ? bills : 0).append("单");
                if (entry.get("客单价") != null) sb.append("，客单价 ¥").append(entry.get("客单价"));
                if (i < storeRank.size() - 1) sb.append("\n");
            }
        }

        // Build chartConfig: horizontal bar — store name vs revenue in 万元
        List<String> chartNames = new ArrayList<>();
        List<Double> chartVals = new ArrayList<>();
        for (Map<String, Object> entry : storeRank) {
            Object name = entry.get("门店");
            Object rev = entry.get("营收");
            double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
            chartNames.add(name != null ? name.toString() : "");
            chartVals.add(toWan(revD));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("总营收", goldResult.get("total_revenue"));
        result.put("门店数", goldResult.get("store_count"));
        result.put("门店营收排行", storeRank);
        if (!storeRank.isEmpty()) {
            result.put("top_store", storeRank.get(0));
        }
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (directTopAnswer) {
            result.put("suppressActionAdvice", true);
        }
        if (!chartNames.isEmpty()) {
            result.put("chartConfig", barChartConfig(
                    "门店营收排行 (万元)", chartNames, chartVals, "万元"));
        }
        return result;
    }

    private static boolean isDirectTopStoreQuestion(Object rawInput) {
        if (rawInput == null) {
            return false;
        }
        String input = rawInput.toString().replaceAll("\\s+", "");
        boolean asksForTop = input.contains("第一名")
                || input.contains("哪家店业绩最好")
                || input.contains("哪家门店业绩最好")
                || input.contains("冠军");
        boolean asksForDirectAnswer = input.contains("直接")
                || input.contains("先告诉")
                || input.contains("只告诉")
                || input.contains("核心依据");
        return asksForTop && asksForDirectAnswer;
    }

    private static boolean matchesStore(Map<String, Object> row, Object storeId, Object storeName) {
        if (hasText(storeId)) {
            Object rowStoreId = row.get("store_id");
            return rowStoreId != null && rowStoreId.toString().equals(storeId.toString());
        }
        if (hasText(storeName)) {
            Object rowStoreName = row.get("store_name");
            return rowStoreName != null && rowStoreName.toString().equals(storeName.toString());
        }
        return true;
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    /** 派生客单价 = 营收/单数 (单数>0 才算)。真实数据, 非编造。 */
    static java.math.BigDecimal deriveAvgTicket(Object revenue, Object billCount) {
        if (revenue == null || billCount == null) return null;
        try {
            java.math.BigDecimal rev = new java.math.BigDecimal(revenue.toString());
            java.math.BigDecimal bill = new java.math.BigDecimal(billCount.toString());
            if (bill.compareTo(java.math.BigDecimal.ZERO) <= 0) return null;
            return rev.divide(bill, 2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) { return null; }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("top_stores");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无门店营收数据。请确认已上传含门店与营业额的经营报表。";
    }
}
