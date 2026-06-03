package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.GoldFinanceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract base for Gold-backed restaurant analytics Tools.
 *
 * <p>Eliminates 8× boilerplate across the concrete Gold restaurant Tools
 * (finance-summary, daily-trend, top-products, slow-sellers, order-type-mix,
 * staff-ranking, discount-breakdown, channel-breakdown) by centralising:
 * <ol>
 *   <li><b>Time-window resolution</b> — parses NL month from the user query, then
 *       falls back to the factory's actual data range from Gold, then falls back to a
 *       12-month trailing window. NEVER uses a "last 7 days from today" window.</li>
 *   <li><b>Gold client call dispatch</b> — delegates to the abstract
 *       {@link #queryGold(String, LocalDate, LocalDate, Map)} method.</li>
 *   <li><b>Fool-proof empty / error degradation</b> — per
 *       {@code fool-proof-design.md} Rule 5: errors and empty results produce an
 *       actionable message, never a dead-end "暂无数据".</li>
 * </ol>
 *
 * <p><b>Not a Spring bean</b> — abstract, so Spring will not register it.
 * Only the concrete {@code @Component} subclasses in this package are beans.
 *
 * <p><b>NL month parsing</b>: copied verbatim from
 * {@link com.cretas.aims.ai.tool.impl.restaurant.RestaurantEconomicsAnalysisTool}
 * (single source of truth). Accepts:
 * <ul>
 *   <li>{@code YYYY年M月} / {@code YYYY-MM} / {@code YYYY/M月}</li>
 *   <li>Relative: {@code 本月}, {@code 这个月}, {@code 当月}, {@code 上月}, {@code 上个月}</li>
 * </ul>
 *
 * <p><b>Data-range response keys</b> (from Python Gold {@code data_range}):
 * {@code min_date}, {@code max_date} (ISO {@code yyyy-MM-dd} strings, nullable),
 * {@code day_count} (int).
 *
 * @since 2026-06-01
 */
@Slf4j
/**
 * factoryId 隔离豁免说明 (@FactoryIsolationExempt): 本类经 GoldBackedRestaurantTool
 * 的 final doExecute(factoryId) 模板方法把 factoryId 传入 queryGold(factoryId, ...)
 * → GoldFinanceClient.fetchX(factoryId, ...), 每个 gold 查询都按 factory_id 租户隔离
 * (Python gold 层 _resolve_tenant)。审计正则无法追踪模板方法 + final 修饰, 故显式豁免;
 * 隔离实际由 factoryId 全程传递保证。
 */
public abstract class GoldBackedRestaurantTool extends AbstractBusinessTool {

    /**
     * Absolute "YYYY年M月" / "YYYY-MM" / "YYYY/M月" found ANYWHERE in the NL query.
     * Copied from RestaurantEconomicsAnalysisTool (NL_ABSOLUTE_MONTH).
     * Month is constrained to 1-12 to avoid matching unrelated digit runs.
     */
    private static final Pattern NL_ABSOLUTE_MONTH = Pattern.compile(
            "(\\d{4})\\s*[年/\\-]\\s*(1[0-2]|0?[1-9])\\s*月?");

    /** Shared Gold client — injected by Spring into each concrete subclass. */
    @Autowired
    protected GoldFinanceClient gold;

    // =========================================================================
    // Abstract contract for subclasses
    // =========================================================================

    /**
     * Perform the actual Gold data fetch for the resolved window.
     *
     * @param factoryId tenant id
     * @param start     inclusive start date (never null — resolveWindow guarantees this)
     * @param end       inclusive end date (never null)
     * @param params    original Tool params (subclasses may read additional fields
     *                  such as {@code top_n}, {@code order})
     * @return raw Gold response map; may be empty but never null
     * @throws Exception on transport failure or parse error; template method catches and
     *                   returns a "服务暂时不可用" result
     */
    protected abstract Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception;

    /**
     * Shape the Gold response into an LLM-friendly answer map.
     *
     * @param goldResult raw map returned by {@link #queryGold}; already checked non-null + non-empty
     * @return the structured result to return from {@code doExecute}
     */
    protected abstract Map<String, Object> format(Map<String, Object> goldResult);

    /**
     * Decide whether the Gold result contains no usable rows.
     *
     * @param goldResult raw map; already checked non-null
     * @return {@code true} when the result carries no meaningful data (e.g. empty lists,
     *         zero total_revenue with zero bill_count)
     */
    protected abstract boolean isEmpty(Map<String, Object> goldResult);

    /**
     * Fool-proof empty message per {@code fool-proof-design.md} Rule 5.
     *
     * <p>Must explain WHY data might be absent AND give a concrete next action.
     * NEVER return a bare "暂无数据" dead-end.
     *
     * @return human-readable message; shown to the LLM as {@code result.message}
     */
    protected abstract String emptyMessage();

    // =========================================================================
    // Template method — doExecute
    // =========================================================================

    /**
     * Template method that wires resolveWindow → queryGold → isEmpty → format.
     * Subclasses implement the four abstract hooks above; they do NOT override this method.
     */
    @Override
    protected final Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) throws Exception {

        // 1. Resolve the analysis window
        LocalDate[] win = resolveWindow(factoryId, params);

        // 2. Call Gold — isolate failures
        Map<String, Object> g;
        try {
            g = queryGold(factoryId, win[0], win[1], params);
        } catch (Exception ex) {
            log.warn("[{}] Gold call failed factory={} range={}..{}: {}",
                    getToolName(), factoryId, win[0], win[1], ex.getMessage());
            Map<String, Object> errResult = new HashMap<>();
            errResult.put("dataAvailable", false);
            errResult.put("message", "数据服务暂时不可用，请稍后重试。");
            return errResult;
        }

        // 3. Empty guard (fool-proof Rule 5 — no dead-end)
        if (g == null || isEmpty(g)) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("dataAvailable", false);
            emptyResult.put("message", emptyMessage());
            emptyResult.put("actionHint",
                    "前往「智能分析 - Excel上传」上传含营业额/菜品销量的经营报表");
            return emptyResult;
        }

        // 4. Format and return
        return format(g);
    }

    // =========================================================================
    // Time-window resolution
    // =========================================================================

    /**
     * Resolve the [start, end] analysis window for a Gold query.
     *
     * <p>Precedence:
     * <ol>
     *   <li>Explicit {@code month} param (LLM-extracted "上月" / "本月" / "YYYY-MM").</li>
     *   <li>Preprocessed {@code startDate} / {@code endDate} ISO params set by
     *       {@code ToolDispatchService} TimeNormalizationRules.</li>
     *   <li>NL absolute month parsed from {@code params.get("userInput")}
     *       (e.g. "2025年12月哪个菜亏钱").</li>
     *   <li>Relative "本月" / "上月" mentioned in the NL query.</li>
     *   <li>Factory actual data range via {@code gold.fetchDataRange(factoryId)} →
     *       returns [min_date, max_date].</li>
     *   <li>Last resort: trailing 12-month window ending today.</li>
     * </ol>
     *
     * @param factoryId tenant id; used only if falling through to fetchDataRange
     * @param params    Tool params; may contain {@code month}, {@code startDate},
     *                  {@code endDate}, {@code userInput}
     * @return {@code [start, end]} — both elements non-null; start ≤ end
     */
    protected LocalDate[] resolveWindow(String factoryId, Map<String, Object> params) {

        // --- Step 1: explicit "month" param ---
        String monthParam = getString(params, "month");
        if (monthParam != null && !monthParam.trim().isEmpty()) {
            LocalDate[] win = parseMonthLabel(monthParam.trim());
            if (win != null) {
                log.debug("[{}] resolveWindow: explicit month param → {}..{}",
                        getToolName(), win[0], win[1]);
                return win;
            }
        }

        // --- Step 2: preprocessed ISO startDate / endDate ---
        String startIso = getString(params, "startDate");
        String endIso = getString(params, "endDate");
        if (startIso != null && endIso != null) {
            LocalDate s = parseIsoDate(startIso);
            LocalDate e = parseIsoDate(endIso);
            if (s != null && e != null && !s.isAfter(e)) {
                log.debug("[{}] resolveWindow: preprocessed ISO dates → {}..{}",
                        getToolName(), s, e);
                return new LocalDate[]{s, e};
            }
        }

        // --- Step 3 & 4: parse raw NL query ---
        String userInput = getString(params, "userInput");
        if (userInput != null) {
            Matcher m = NL_ABSOLUTE_MONTH.matcher(userInput);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                int monthNum = Integer.parseInt(m.group(2));
                String ym = String.format(Locale.ROOT, "%04d-%02d", year, monthNum);
                LocalDate[] win = parseMonthLabel(ym);
                if (win != null) {
                    log.debug("[{}] resolveWindow: NL absolute month '{}' → {}..{}",
                            getToolName(), ym, win[0], win[1]);
                    return win;
                }
            }
            if (userInput.contains("本月") || userInput.contains("这个月")
                    || userInput.contains("当月")) {
                LocalDate[] win = parseMonthLabel("本月");
                log.debug("[{}] resolveWindow: NL 本月 → {}..{}", getToolName(), win[0], win[1]);
                return win;
            }
            if (userInput.contains("上月") || userInput.contains("上个月")) {
                LocalDate[] win = parseMonthLabel("上月");
                log.debug("[{}] resolveWindow: NL 上月 → {}..{}", getToolName(), win[0], win[1]);
                return win;
            }
        }

        // --- Step 5: factory actual data range ---
        try {
            Map<String, Object> range = gold.fetchDataRange(factoryId);
            String minDateStr = range.get("min_date") != null ? range.get("min_date").toString() : null;
            String maxDateStr = range.get("max_date") != null ? range.get("max_date").toString() : null;
            LocalDate minDate = parseIsoDate(minDateStr);
            LocalDate maxDate = parseIsoDate(maxDateStr);
            if (minDate != null && maxDate != null && !minDate.isAfter(maxDate)) {
                log.debug("[{}] resolveWindow: Gold data-range → {}..{}",
                        getToolName(), minDate, maxDate);
                return new LocalDate[]{minDate, maxDate};
            }
            // data-range returned nulls (factory has no Gold data yet)
            // Fall through to last-resort — still better than empty response with no hint
            log.debug("[{}] resolveWindow: Gold data-range null/empty for factory={}; using fallback",
                    getToolName(), factoryId);
        } catch (Exception ex) {
            log.warn("[{}] resolveWindow: fetchDataRange failed factory={}: {}; using fallback",
                    getToolName(), factoryId, ex.getMessage());
        }

        // --- Step 6: last-resort 12-month trailing window ---
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(12);
        log.debug("[{}] resolveWindow: last-resort fallback → {}..{}", getToolName(), start, end);
        return new LocalDate[]{start, end};
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Parse a month label into a [firstDay, lastDay] window.
     *
     * @param label one of: {@code "上月"}, {@code "本月"}, {@code "YYYY-MM"}
     * @return window or {@code null} if the label is unrecognised
     */
    private LocalDate[] parseMonthLabel(String label) {
        if (label == null) return null;
        switch (label) {
            case "本月":
            case "这个月":
            case "当月": {
                YearMonth ym = YearMonth.now();
                return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
            }
            case "上月":
            case "上个月": {
                YearMonth ym = YearMonth.now().minusMonths(1);
                return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
            }
            default: {
                // Try ISO "YYYY-MM"
                if (label.length() >= 7) {
                    try {
                        YearMonth ym = YearMonth.parse(label.substring(0, 7));
                        return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
                    } catch (DateTimeParseException ignored) {
                        // fall through
                    }
                }
                return null;
            }
        }
    }

    /**
     * Safely parse an ISO {@code yyyy-MM-dd} date string.
     *
     * @param s date string; may be null or malformed
     * @return parsed date, or {@code null} on any failure
     */
    private LocalDate parseIsoDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // =========================================================================
    // Subclass contract overrides that must pass through to AbstractBusinessTool
    // =========================================================================

    /**
     * Gold Tools read-only by default; no required parameters (window is self-resolving).
     * Concrete subclasses may override if they need additional required params.
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    // =========================================================================
    // ECharts chart config helpers — used by format() in each concrete tool
    // =========================================================================

    /**
     * Build a horizontal bar chart config for ECharts.
     *
     * <p>The first entry in {@code categories}/{@code values} is rendered at the
     * <em>bottom</em> of a horizontal bar chart by default; we reverse both lists
     * so that the highest-ranked item (index 0) appears at the <em>top</em>.
     *
     * @param title      chart title (shown in the UI header above the chart)
     * @param categories labels for each bar (e.g. dish names, store names)
     * @param values     numeric values parallel to {@code categories}
     * @param unitName   axis unit label (e.g. "万元", "份", "单")
     * @return chartConfig map shaped {@code {type, title, option}}
     */
    protected static Map<String, Object> barChartConfig(
            String title,
            List<String> categories,
            List<? extends Number> values,
            String unitName) {

        // Reverse so highest value (index 0) appears at the top of the bar chart.
        List<String> cat = new ArrayList<>(categories);
        Collections.reverse(cat);
        List<Object> val = new ArrayList<>(values);
        Collections.reverse(val);

        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "axis"));
        opt.put("grid", Map.of("left", "3%", "right", "6%",
                "bottom", "3%", "top", "8%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "value", "name", unitName));
        opt.put("yAxis", Map.of("type", "category", "data", cat,
                "axisLabel", Map.of("fontSize", 11)));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("type", "bar");
        series.put("data", val);
        series.put("itemStyle", Map.of("color", "#5470c6"));
        series.put("label", Map.of("show", true, "position", "right"));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "bar");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }

    /**
     * Build a line chart config for ECharts (time series — e.g. monthly revenue trend).
     *
     * <p>Unlike {@link #barChartConfig}, categories are NOT reversed — a line chart
     * reads left→right chronologically, so the caller passes them already ascending
     * (earliest first). The AIQuery renderer uses the emitted {@code option} directly.
     *
     * @param title      chart title (shown in the UI header above the chart)
     * @param categories x-axis labels in chronological order (e.g. "2025-01", "2025-02")
     * @param values     numeric values parallel to {@code categories}
     * @param unitName   y-axis unit label (e.g. "万元")
     * @param seriesName legend / series name (e.g. "营收")
     * @return chartConfig map shaped {@code {type, title, option}}
     */
    protected static Map<String, Object> lineChartConfig(
            String title,
            List<String> categories,
            List<? extends Number> values,
            String unitName,
            String seriesName) {

        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "axis"));
        opt.put("grid", Map.of("left", "3%", "right", "4%",
                "bottom", "3%", "top", "10%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "category", "data", new ArrayList<>(categories),
                "axisLabel", Map.of("fontSize", 11, "rotate", categories.size() > 6 ? 40 : 0)));
        opt.put("yAxis", Map.of("type", "value", "name", unitName));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", seriesName);
        series.put("type", "line");
        series.put("smooth", true);
        series.put("data", new ArrayList<>(values));
        series.put("itemStyle", Map.of("color", "#2D8B57"));
        series.put("label", Map.of("show", false));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "line");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }

    /**
     * Build a pie chart config for ECharts.
     *
     * @param title  chart title
     * @param names  slice labels
     * @param values slice values parallel to {@code names}
     * @return chartConfig map shaped {@code {type, title, option}}
     */
    protected static Map<String, Object> pieChartConfig(
            String title,
            List<String> names,
            List<? extends Number> values) {

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            data.add(Map.of("name", names.get(i), "value", values.get(i)));
        }

        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c} ({d}%)"));
        opt.put("legend", Map.of("top", "bottom"));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("type", "pie");
        series.put("radius", "60%");
        series.put("data", data);
        series.put("label", Map.of("formatter", "{b} {d}%"));
        opt.put("series", List.of(series));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "pie");
        cfg.put("title", title);
        cfg.put("option", opt);
        return cfg;
    }

    /**
     * Convert a raw amount (in yuan) to 万元 rounded to 1 decimal place.
     *
     * @param yuan raw value in yuan
     * @return value in 万元 (1 decimal)
     */
    protected static double toWan(double yuan) {
        return Math.round(yuan / 1000.0) / 10.0;
    }
}
