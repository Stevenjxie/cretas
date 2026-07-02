package com.cretas.aims.ai.tool.impl.restaurant.gold;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周末 vs 周中营收对比工具（Gold 层 daily-trend 按星期分类聚合）。
 *
 * <p>从 Gold 层 daily-trend 拿到按日明细后，在 Java 侧将每天分类为
 * 周末（SATURDAY/SUNDAY）或周中，分别统计总营收、天数、日均营收，
 * 并给出简明结论。
 *
 * <p>适用意图：周末周中 / 周末生意好还是平日 / 礼拜几卖得好。
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
public class RestaurantWeekdayWeekendGoldTool extends GoldBackedRestaurantTool {

    @Override
    public String getToolName() {
        return "restaurant_weekday_weekend_gold";
    }

    @Override
    public String getDescription() {
        return "周末 vs 周中营收对比(gold 层 daily-trend 按星期聚合)。适用: 周末周中/周末生意好还是平日/礼拜几卖得好。";
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
        return gold.fetchDailyTrend(factoryId, start, end);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> format(Map<String, Object> goldResult) {
        List<Map<String, Object>> rawPoints =
                goldResult.get("points") instanceof List
                        ? (List<Map<String, Object>>) goldResult.get("points")
                        : Collections.emptyList();

        double totalWeekend = 0.0;
        int nWeekend = 0;
        double totalWeekday = 0.0;
        int nWeekday = 0;

        for (Map<String, Object> pt : rawPoints) {
            Object dateObj = pt.get("date");
            Object revObj = pt.get("revenue");
            if (dateObj == null || revObj == null) continue;
            double rev = ((Number) revObj).doubleValue();
            LocalDate d;
            try {
                d = LocalDate.parse(dateObj.toString().substring(0, 10));
            } catch (Exception e) {
                log.debug("[{}] Skipping unparseable date: {}", getToolName(), dateObj);
                continue;
            }
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                totalWeekend += rev;
                nWeekend++;
            } else {
                totalWeekday += rev;
                nWeekday++;
            }
        }

        double avgWeekend = nWeekend > 0 ? round2(totalWeekend / nWeekend) : 0.0;
        double avgWeekday = nWeekday > 0 ? round2(totalWeekday / nWeekday) : 0.0;

        String conclusion;
        double baseline = Math.max(Math.max(avgWeekend, avgWeekday), 1.0);
        double diffRatio = Math.abs(avgWeekend - avgWeekday) / baseline;
        if (diffRatio < 0.03) {
            conclusion = "周末和周中基本持平";
        } else if (avgWeekend > avgWeekday) {
            conclusion = "周末日均更高";
        } else if (avgWeekday > avgWeekend) {
            conclusion = "周中日均更高";
        } else {
            conclusion = "周末与周中日均相近";
        }

        Map<String, Object> weekendStats = new LinkedHashMap<>();
        weekendStats.put("日均营收", avgWeekend);
        weekendStats.put("总营收", round2(totalWeekend));
        weekendStats.put("天数", nWeekend);

        Map<String, Object> weekdayStats = new LinkedHashMap<>();
        weekdayStats.put("日均营收", avgWeekday);
        weekdayStats.put("总营收", round2(totalWeekday));
        weekdayStats.put("天数", nWeekday);

        String msg = "周末 vs 周中营收对比（按日均）：\n"
                + "周末 日均" + fmtAmt(avgWeekend) + "（" + nWeekend + "天）\n"
                + "周中 日均" + fmtAmt(avgWeekday) + "（" + nWeekday + "天）\n"
                + "结论：" + conclusion;

        // Build chartConfig: horizontal bar — 周末 vs 周中 daily average revenue in 万元
        List<String> chartCats = new ArrayList<>();
        chartCats.add("周末");
        chartCats.add("周中");
        List<Double> chartVals = new ArrayList<>();
        chartVals.add(toWan(avgWeekend));
        chartVals.add(toWan(avgWeekday));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("周末", weekendStats);
        result.put("周中", weekdayStats);
        result.put("结论", conclusion);
        result.put("dataAvailable", true);
        result.put("message", msg);
        result.put("chartConfig", barChartConfig(
                "周末 vs 周中 日均营收 (万元)", chartCats, chartVals, "万元"));
        return result;
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
        return "本期暂无按日营收数据, 无法计算周末/周中对比。请确认上传含日期与营业额的经营报表。";
    }

    /** Round to 2 decimal places. */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
