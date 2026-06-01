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

        List<Map<String, Object>> storeRank = new ArrayList<>();
        for (Map<String, Object> row : rawStores) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("门店", row.get("store_name"));
            entry.put("营收", row.get("revenue"));
            entry.put("单数", row.get("bill_count"));
            storeRank.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("总营收", goldResult.get("total_revenue"));
        result.put("门店数", goldResult.get("store_count"));
        result.put("门店营收排行", storeRank);
        result.put("dataAvailable", true);
        return result;
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
