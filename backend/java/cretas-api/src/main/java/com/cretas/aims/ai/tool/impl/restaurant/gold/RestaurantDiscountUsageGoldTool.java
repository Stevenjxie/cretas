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
 * 优惠券/折扣使用情况查询工具（Gold 层 discount-breakdown）。
 *
 * <p>Python Gold 的 {@code discount_breakdown} 查询返回的顶层 list key 是 {@code "discounts"}，
 * 每条 item 包含 {@code discount_name}、{@code amount}、{@code bill_count}、{@code share_pct}。
 *
 * <p>适用意图：优惠券使用 / 折扣率 / 代金券 / 满减。
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
public class RestaurantDiscountUsageGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_discount_usage_gold";
    }

    @Override
    public String getDescription() {
        return "优惠券/折扣使用情况(gold 层 discount-breakdown)。适用: 优惠券使用/折扣率/代金券/满减。";
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
        return gold.fetchDiscountBreakdown(factoryId, start, end, 10);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        Object rawStart = goldResult.get("start_date");
        Object rawEnd = goldResult.get("end_date");
        String period = (rawStart != null ? rawStart.toString() : "?")
                + " 至 " + (rawEnd != null ? rawEnd.toString() : "?");

        // Python Gold returns the list under key "discounts" (not "discount_breakdown").
        List<Map<String, Object>> rawItems =
                goldResult.get("discounts") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("discounts")
                        : Collections.emptyList();

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map<String, Object> row : rawItems) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("优惠类型", row.get("discount_name"));
            entry.put("优惠金额", row.get("amount"));
            entry.put("使用笔数", row.get("bill_count"));
            ranking.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("优惠使用排行", ranking);
        result.put("dataAvailable", true);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        // Python Gold returns the list under key "discounts".
        Object raw = goldResult.get("discounts");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无优惠/折扣使用数据。";
    }
}
