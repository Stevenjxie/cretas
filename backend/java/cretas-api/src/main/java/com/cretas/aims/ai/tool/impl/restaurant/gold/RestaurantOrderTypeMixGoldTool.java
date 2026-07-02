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
 * 堂食 vs 外卖营收占比查询工具（Gold 层 agg_daily_order_type_meal）。
 *
 * <p>适用意图：外卖占比 / 堂食外卖对比 / 外卖生意。
 *
 * <p>注意：此工具查询的是服务模式（堂食/外卖），而非支付渠道（微信/美团）。
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
public class RestaurantOrderTypeMixGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_order_type_mix_gold";
    }

    @Override
    public String getDescription() {
        return "堂食 vs 外卖 营收占比(gold 层 order_type)。适用: 外卖占比/堂食外卖对比/外卖生意。";
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
        return gold.fetchOrderTypeMix(factoryId, start, end);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        Object rawStart = goldResult.get("start_date");
        Object rawEnd = goldResult.get("end_date");
        String period = (rawStart != null ? rawStart.toString() : "?")
                + " 至 " + (rawEnd != null ? rawEnd.toString() : "?");

        boolean revenueEstimated = Boolean.TRUE.equals(goldResult.get("revenue_estimated"));
        Object estimationNote = goldResult.get("estimation_note");

        List<Map<String, Object>> rawTypes =
                goldResult.get("order_types") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("order_types")
                        : Collections.emptyList();

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Map<String, Object> row : rawTypes) {
            Object pctRaw = row.get("revenue_pct");
            String pctStr = pctRaw != null ? pctRaw.toString() + "%" : null;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("类型", row.get("order_type"));
            entry.put("营收", row.get("revenue"));
            entry.put("占比", pctStr);
            entry.put("单数", row.get("bill_count"));
            breakdown.add(entry);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("堂食 vs 外卖（").append(period).append("）：\n");
        if (revenueEstimated && estimationNote != null) {
            sb.append(estimationNote).append("\n");
        }
        for (int i = 0; i < breakdown.size(); i++) {
            Map<String, Object> entry = breakdown.get(i);
            Object rev = entry.get("营收");
            double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
            Object pct = entry.get("占比");
            Object bills = entry.get("单数");
            sb.append(entry.get("类型")).append("：")
                    .append(fmtAmt(revD))
                    .append("（").append(pct != null ? pct : "N/A").append("）")
                    .append("，").append(bills != null ? bills : 0).append("单");
            if (i < breakdown.size() - 1) sb.append("\n");
        }

        // Build chartConfig: pie — order type vs revenue
        List<String> pieNames = new ArrayList<>();
        List<Double> pieVals = new ArrayList<>();
        for (Map<String, Object> entry : breakdown) {
            Object type = entry.get("类型");
            Object rev = entry.get("营收");
            double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
            pieNames.add(type != null ? type.toString() : "");
            pieVals.add(revD);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("统计周期", period);
        result.put("总营收", goldResult.get("total_revenue"));
        result.put("堂食外卖占比", breakdown);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!pieNames.isEmpty()) {
            result.put("chartConfig", pieChartConfig(
                    "堂食 vs 外卖 (营收占比)", pieNames, pieVals));
        }
        return result;
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("order_types");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无堂食/外卖拆分数据。请确认上传数据含订单类型(堂食/外卖)字段。";
    }
}
