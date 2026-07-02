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
 * 营收趋势 / 同比环比分析工具（Gold 层 trend-bundle）。
 *
 * <p>从 Gold 层 {@code /api/smartbi/gold/trend-bundle} 一次取回三视图
 * （dailyTrend + weekdayWeekend + monthlyTrend），在 Java 侧汇总为：
 * 月度营收趋势（峰值月 / 最低月 + 首尾环比方向）+ 周末/工作日日均对比 + 月度折线图。
 *
 * <p><b>为什么需要它</b>：AI问答里 "同比环比 / 趋势 / 增长下降" 类问题原先路由到
 * COMPREHENSIVE_SYNTHESIS（综合分析），但综合分析的 FactBook 没有月度时间序列，
 * 答出来是 "无法计算同比环比 / 趋势"。本工具直查 gold 月度营收序列，能真正给出趋势。
 *
 * <p>适用意图：营收趋势 / 同比 / 环比 / 增长和下降趋势 / 月度变化 / 走势。
 *
 * @since 2026-06-03
 */
@Slf4j
@Component
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 本类经 GoldBackedRestaurantTool
 * 的 final doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...)
 * → GoldFinanceClient.fetchTrendBundle(factoryId, ...), 每个 gold 查询都按 factory_id
 * 租户隔离 (Python gold 层 _resolve_tenant)。审计正则无法追踪模板方法 + final 修饰,
 * 故显式豁免; 隔离实际由 factoryId 全程传递保证。
 */
public class RestaurantRevenueTrendGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_revenue_trend_gold";
    }

    @Override
    public String getDescription() {
        return "营收趋势/同比环比分析(gold 层 trend_bundle, 全部历史月度营收趋势+峰谷+环比)。"
                + "适用: 趋势/同比/环比/增长下降/月度变化。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> monthProp = new HashMap<>();
        monthProp.put("type", "string");
        monthProp.put("description", "分析月份或区间, 如 '2026年3月' / '上月', 不传默认全部历史");

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
        // resolveWindow always returns a concrete window (factory's full Gold span
        // when no month is specified), so passing it gives all-history by default.
        return gold.fetchTrendBundle(factoryId, start, end);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        List<Map<String, Object>> monthly =
                goldResult.get("monthlyTrend") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("monthlyTrend")
                        : Collections.emptyList();

        // monthlyTrend is ascending by month (Python ORDER BY date_trunc).
        // Build a clean per-month list + find peak / trough.
        List<Map<String, Object>> byMonth = new ArrayList<>();
        String peakMonth = null;
        double peakRev = Double.NEGATIVE_INFINITY;
        String troughMonth = null;
        double troughRev = Double.POSITIVE_INFINITY;
        for (Map<String, Object> m : monthly) {
            Object moObj = m.get("month");
            Object revObj = m.get("revenue");
            if (moObj == null || revObj == null) continue;
            String mo = moObj.toString();
            double rev = revObj instanceof Number ? ((Number) revObj).doubleValue() : 0.0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("月份", mo);
            entry.put("营收", Math.round(rev * 100.0) / 100.0);
            byMonth.add(entry);
            if (rev > peakRev) {
                peakRev = rev;
                peakMonth = mo;
            }
            if (rev < troughRev) {
                troughRev = rev;
                troughMonth = mo;
            }
        }

        // 排除当前进行中的月份 (尚未结束, 营收只是月初部分) 再算趋势/环比 —— 否则
        // (last - first)/first 把"半个月 vs 整月"当成断崖式下跌 (实测 -100%)。
        // 展示的 byMonth 仍保留当前月 (标"进行中"), 但趋势方向/环比% 只用已完结月份。
        String curMonthPrefix = java.time.YearMonth.now().toString(); // e.g. "2026-06"
        boolean lastIsCurrentMonth = !byMonth.isEmpty()
                && byMonth.get(byMonth.size() - 1).get("月份") != null
                && byMonth.get(byMonth.size() - 1).get("月份").toString().startsWith(curMonthPrefix);
        boolean lastLooksIncomplete = false;
        if (byMonth.size() >= 2) {
            double prev = ((Number) byMonth.get(byMonth.size() - 2).get("营收")).doubleValue();
            double last = ((Number) byMonth.get(byMonth.size() - 1).get("营收")).doubleValue();
            lastLooksIncomplete = prev > 0 && last > 0 && last < prev * 0.25;
        }
        boolean lastIsPartial = lastIsCurrentMonth || lastLooksIncomplete;
        if (lastIsPartial) {
            byMonth.get(byMonth.size() - 1).put("进行中", true);
        }
        List<Map<String, Object>> completed = (lastIsPartial && byMonth.size() >= 2)
                ? byMonth.subList(0, byMonth.size() - 1) : byMonth;

        if (lastIsPartial && !completed.isEmpty()) {
            peakMonth = null;
            peakRev = Double.NEGATIVE_INFINITY;
            troughMonth = null;
            troughRev = Double.POSITIVE_INFINITY;
            for (Map<String, Object> m : completed) {
                String mo = m.get("月份").toString();
                double rev = ((Number) m.get("营收")).doubleValue();
                if (rev > peakRev) {
                    peakRev = rev;
                    peakMonth = mo;
                }
                if (rev < troughRev) {
                    troughRev = rev;
                    troughMonth = mo;
                }
            }
        }

        // First→last month-over-month direction (环比 over the whole span, 已完结月份).
        String momDirection = "持平";
        double momPct = 0.0;
        if (completed.size() >= 2) {
            double first = ((Number) completed.get(0).get("营收")).doubleValue();
            double last = ((Number) completed.get(completed.size() - 1).get("营收")).doubleValue();
            if (first > 0) {
                momPct = Math.round(((last - first) / first) * 1000.0) / 10.0; // 1 decimal %
            }
            if (last > first) {
                momDirection = "上升";
            } else if (last < first) {
                momDirection = "下降";
            }
        }

        // Latest-vs-previous month 环比 (last two 已完结 months) — the most common "环比" ask.
        String latestMomDirection = null;
        double latestMomPct = 0.0;
        if (completed.size() >= 2) {
            double prev = ((Number) completed.get(completed.size() - 2).get("营收")).doubleValue();
            double last = ((Number) completed.get(completed.size() - 1).get("营收")).doubleValue();
            if (prev > 0) {
                latestMomPct = Math.round(((last - prev) / prev) * 1000.0) / 10.0;
            }
            latestMomDirection = last > prev ? "上升" : (last < prev ? "下降" : "持平");
        }

        // Weekend vs weekday daily-average.
        Map<String, Object> ww = goldResult.get("weekdayWeekend") instanceof Map
                ? (Map<String, Object>) goldResult.get("weekdayWeekend")
                : Collections.emptyMap();
        double weekdayAvg = numVal(ww.get("weekdayAvg"));
        double weekendAvg = numVal(ww.get("weekendAvg"));

        // Build human-readable message.
        StringBuilder sb = new StringBuilder();
        if (peakMonth != null) {
            sb.append("月度营收趋势（共").append(byMonth.size()).append("个月）：\n");
            sb.append("峰值月份 ").append(peakMonth).append("（").append(fmtAmt(peakRev)).append("）");
            if (troughMonth != null && !troughMonth.equals(peakMonth)) {
                sb.append("，最低月份 ").append(troughMonth)
                        .append("（").append(fmtAmt(troughRev)).append("）");
            }
            sb.append("。\n");
            if (lastIsPartial && !byMonth.isEmpty()) {
                String partialMonth = byMonth.get(byMonth.size() - 1).get("月份").toString();
                sb.append("注意：").append(partialMonth)
                        .append(" 数据疑似未完整结账，暂不拿它判断下滑。\n");
            }
            if (completed.size() >= 2) {
                String firstMo = completed.get(0).get("月份").toString();
                String lastMo = completed.get(completed.size() - 1).get("月份").toString();
                sb.append("整体走势 ").append(firstMo).append(" → ").append(lastMo)
                        .append(" 营收").append(momDirection);
                if (momPct != 0.0) {
                    sb.append("（").append(fmtPct(momPct)).append("）");
                }
                sb.append("。\n");
                if (latestMomDirection != null) {
                    sb.append("最新一月环比上月").append(latestMomDirection);
                    if (latestMomPct != 0.0) {
                        sb.append("（").append(fmtPct(latestMomPct)).append("）");
                    }
                    sb.append("。\n");
                }
            }
        }
        if (weekdayAvg > 0 || weekendAvg > 0) {
            String wwConcl = weekendAvg > weekdayAvg ? "周末日均更高"
                    : (weekdayAvg > weekendAvg ? "周中日均更高" : "周末与周中日均相近");
            double baseline = Math.max(Math.max(weekdayAvg, weekendAvg), 1.0);
            if (Math.abs(weekendAvg - weekdayAvg) / baseline < 0.03) {
                wwConcl = "周末和周中基本持平";
            }
            sb.append("周末日均").append(fmtAmt(weekendAvg))
                    .append("，周中日均").append(fmtAmt(weekdayAvg))
                    .append("（").append(wwConcl).append("）。");
        }

        // chartConfig: line chart — month vs revenue in 万元 (chronological, ascending).
        List<String> chartMonths = new ArrayList<>();
        List<Double> chartVals = new ArrayList<>();
        for (Map<String, Object> m : byMonth) {
            chartMonths.add(m.get("月份").toString());
            double revD = ((Number) m.get("营收")).doubleValue();
            chartVals.add(toWan(revD));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("月度营收趋势", byMonth);
        result.put("峰值月份", peakMonth);
        result.put("峰值营收", peakMonth != null ? Math.round(peakRev * 100.0) / 100.0 : null);
        result.put("最低月份", troughMonth);
        result.put("整体环比方向", momDirection);
        result.put("整体环比百分比", momPct);
        if (latestMomDirection != null) {
            result.put("最新月环比方向", latestMomDirection);
            result.put("最新月环比百分比", latestMomPct);
        }
        Map<String, Object> wwOut = new LinkedHashMap<>();
        wwOut.put("周末日均", Math.round(weekendAvg * 100.0) / 100.0);
        wwOut.put("周中日均", Math.round(weekdayAvg * 100.0) / 100.0);
        result.put("周末周中", wwOut);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!chartMonths.isEmpty()) {
            result.put("chartConfig", lineChartConfig(
                    "月度营收趋势 (万元)", chartMonths, chartVals, "万元", "营收"));
        }
        return result;
    }

    private static double numVal(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private static String fmtAmt(double v) {
        return v >= 10_000 ? String.format("%.1f万", v / 10_000) : String.format("%.0f", v);
    }

    private static String fmtPct(double pct) {
        return (pct > 0 ? "+" : "") + String.format("%.1f%%", pct);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean isEmpty(Map<String, Object> goldResult) {
        Object raw = goldResult.get("monthlyTrend");
        if (!(raw instanceof List)) return true;
        return ((List<?>) raw).isEmpty();
    }

    @Override
    protected String emptyMessage() {
        return "本期暂无按日营收数据, 无法计算营收趋势/同比环比。"
                + "请确认已上传含日期与营业额的经营报表。";
    }
}
