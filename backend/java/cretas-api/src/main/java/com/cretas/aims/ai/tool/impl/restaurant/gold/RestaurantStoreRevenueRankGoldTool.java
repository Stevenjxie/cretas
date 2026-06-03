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
        return gold.fetchFinanceSummary(factoryId, start, end, 5);
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

        Object storeCountObj = goldResult.get("store_count");
        int storeCount = storeCountObj instanceof Number ? ((Number) storeCountObj).intValue() : rawStores.size();

        List<Map<String, Object>> storeRank = new ArrayList<>();
        for (Map<String, Object> row : rawStores) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("门店", row.get("store_name"));
            entry.put("营收", row.get("revenue"));
            entry.put("单数", row.get("bill_count"));
            java.math.BigDecimal avgTicket = deriveAvgTicket(row.get("revenue"), row.get("bill_count"));
            if (avgTicket != null) entry.put("客单价", avgTicket);
            storeRank.add(entry);
        }

        StringBuilder sb = new StringBuilder();
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
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!chartNames.isEmpty()) {
            result.put("chartConfig", barChartConfig(
                    "门店营收排行 (万元)", chartNames, chartVals, "万元"));
        }
        return result;
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
