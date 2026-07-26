package com.cretas.aims.ai.tool.impl.restaurant.gold;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 营收峰值月份查询工具（Gold 层 daily-trend 按月聚合）。
 *
 * <p>从 Gold 层 daily-trend 拿到按日明细后，在 Java 侧按 YYYY-MM 分组累加营收，
 * 找出总营收最高的月份。无需额外 DB 查询——daily-trend 已包含所需明细。
 *
 * <p>适用意图：峰值月份 / 哪个月营业额最高 / 月度高峰。
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
public class RestaurantPeakMonthGoldTool extends GoldBackedRestaurantTool {

    private static final String USER_INPUT = "userInput";

    @Override
    public String getToolName() {
        return "restaurant_peak_month_gold";
    }

    @Override
    public String getDescription() {
        return "营收峰值月份(gold 层 daily-trend 按月聚合)。适用: 峰值月份/哪个月营业额最高/月度高峰。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new HashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份或区间, 如 '2026年3月' / '上月', 不传默认全部数据");

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
        Map<String, Object> result = gold.fetchDailyTrend(factoryId, start, end);
        if (result == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(result);
        if (params != null && params.get(USER_INPUT) != null) {
            copy.put(USER_INPUT, params.get(USER_INPUT));
        }
        return copy;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        List<Map<String, Object>> rawPoints =
                goldResult.get("points") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("points")
                        : Collections.emptyList();

        // Group by YYYY-MM (first 7 chars of ISO date string), sum revenue per month.
        Map<String, Double> monthRevMap = new LinkedHashMap<>();
        for (Map<String, Object> pt : rawPoints) {
            Object dateObj = pt.get("date");
            Object revObj = pt.get("revenue");
            if (dateObj == null || revObj == null) continue;
            String dateStr = dateObj.toString();
            if (dateStr.length() < 7) continue;
            String ym = dateStr.substring(0, 7); // "YYYY-MM"
            double rev = revObj instanceof Number ? ((Number) revObj).doubleValue() : 0.0;
            monthRevMap.merge(ym, rev, Double::sum);
        }

        // Build sorted list (descending by revenue).
        List<Map<String, Object>> byMonth = new ArrayList<>();
        for (Map.Entry<String, Double> e : monthRevMap.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("月份", e.getKey());
            // Round to 2 decimal places for display.
            entry.put("营收", Math.round(e.getValue() * 100.0) / 100.0);
            byMonth.add(entry);
        }
        byMonth.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("营收")).doubleValue()).reversed());

        String peakMonth = byMonth.isEmpty() ? null : (String) byMonth.get(0).get("月份");
        Object peakRevenue = byMonth.isEmpty() ? null : byMonth.get(0).get("营收");

        // Build human-readable message.
        StringBuilder sb = new StringBuilder();
        if (peakMonth != null && peakRevenue instanceof Number) {
            sb.append("营收峰值月份：").append(peakMonth)
                    .append("（").append(fmtAmt(((Number) peakRevenue).doubleValue())).append("）。\n");
        }
        if (!byMonth.isEmpty()) {
            sb.append("各月营收排行：");
            int limit = Math.min(byMonth.size(), 6);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> m = byMonth.get(i);
                Object rev = m.get("营收");
                double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
                sb.append(i + 1).append(". ").append(m.get("月份"))
                        .append(" ").append(fmtAmt(revD));
                if (i < limit - 1) sb.append("\n");
            }
        }
        boolean asksWhy = asksForCause(goldResult.get(USER_INPUT));
        if (asksWhy && peakMonth != null) {
            sb.append("\n原因说明：当前结果使用的是按日营收数据，只能证明 ")
                    .append(peakMonth)
                    .append(" 的营收最高，不能仅凭营收序列断定原因。")
                    .append("要进一步判断，请联查该月与其他月份的客流、订单量、客单价、")
                    .append("菜品销量、活动、天气、评价和排班数据；缺少的维度应保持为空，不能编造。");
        }

        // Build chartConfig: horizontal bar — month vs revenue in 万元 (same order as message)
        List<String> chartMonths = new ArrayList<>();
        List<Double> chartVals = new ArrayList<>();
        for (Map<String, Object> m : byMonth) {
            Object mo = m.get("月份");
            Object rev = m.get("营收");
            double revD = rev instanceof Number ? ((Number) rev).doubleValue() : 0.0;
            chartMonths.add(mo != null ? mo.toString() : "");
            chartVals.add(toWan(revD));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("按月营收", byMonth);
        result.put("峰值月份", peakMonth);
        result.put("峰值营收", peakRevenue);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!chartMonths.isEmpty()) {
            result.put("chartConfig", barChartConfig(
                    "各月营收 (万元)", chartMonths, chartVals, "万元"));
        }
        if (asksWhy) {
            result.put("suggestedFollowups", List.of(
                    Map.of("label", "拆解订单与客单价", "question", "对比峰值月和次高月的订单量与客单价"),
                    Map.of("label", "补充经营维度", "question", "结合客流、菜品、活动、天气、评价和排班分析峰值月原因")));
        }
        return result;
    }

    @Override
    protected boolean shouldDelegateToTieredIntent(String userInput) {
        // A real peak-month question owns the comparison across all available
        // months. Delegating it to the generic missing-time gate creates a
        // circular “which single month?” clarification. Keep the inherited
        // delegate behavior for an accidentally misrouted non-peak question so
        // this guard does not silently widen the tool's responsibility.
        return !isCrossMonthComparisonQuestion(userInput);
    }

    private static boolean isCrossMonthComparisonQuestion(String rawInput) {
        if (rawInput == null) {
            return false;
        }
        String input = rawInput.replaceAll("\\s+", "");
        boolean monthObject = input.contains("哪个月")
                || input.contains("哪月")
                || input.contains("哪个月份")
                || input.contains("哪一个月")
                || input.contains("月份")
                || input.contains("月度");
        boolean comparison = input.contains("最高")
                || input.contains("最低")
                || input.contains("峰值")
                || input.contains("高峰")
                || input.contains("冠军")
                || input.contains("第一")
                || input.contains("最多")
                || input.contains("最少")
                || input.contains("排行")
                || input.contains("排名");
        return monthObject && comparison;
    }

    private static boolean asksForCause(Object rawInput) {
        if (rawInput == null) {
            return false;
        }
        String input = rawInput.toString().replaceAll("\\s+", "");
        return input.contains("为什么")
                || input.contains("原因")
                || input.contains("怎么造成")
                || input.contains("为何");
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("points");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无按日营收数据, 无法计算峰值月份。请确认上传含日期与营业额的经营报表。";
    }
}
