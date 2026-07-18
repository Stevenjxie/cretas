package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.PythonSmartBIClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收入管理报表生成 AI Tool — 青花椒 (R_QINGHUAJIAO_REAL) and future R_*_REAL chains.
 *
 * <p>Spec §8.4 / Task H3. Thin Java wrapper around Python /prepare endpoint.
 * Mirrors {@code FinancialChartGenerateTool} pattern.
 *
 * <p>Intent binding: see Flyway migration
 * {@code V20260513_02__revenue_report_intent.sql} which inserts a row into
 * {@code ai_intent_configs} with {@code intent_code = REVENUE_REPORT_GENERATE}
 * and {@code tool_name = revenue_report_generate} (matches {@link #getToolName()}).
 *
 * <p>LLM contract:
 * <ul>
 *   <li>{@code date_from}/{@code date_to} REQUIRED, YYYY-MM-DD; LLM must resolve
 *     '上周'/'本月' etc. before invoking.</li>
 *   <li>{@code store_names} optional; empty → all stores.</li>
 *   <li>{@code meal_periods} optional enum [午市, 晚市]; LLM may pass synonyms
 *     (下午茶, 夜宵) — Tool internalizes via {@link MealPeriodNormalizer}.</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-13
 */
@Slf4j
@Component
public class RevenueReportGenerateTool extends AbstractBusinessTool {

    @Autowired
    private PythonSmartBIClient pythonClient;

    @Override
    public String getToolName() {
        return "revenue_report_generate";
    }

    @Override
    public String getDescription() {
        return "生成餐饮收入管理报表（同比/环比/堂食外卖占比/客单人数分析）。" +
                "参数: date_from / date_to 可选 YYYY-MM-DD（也接受 '本月'/'上月'/'2025-03'/'2025年3月'，省略默认本月）；" +
                "store_names 可选，省略 = 全部门店；" +
                "meal_periods 可选 enum ['午市','晚市']（'下午茶'/'夜宵' 自动归一）。" +
                "适用场景：客户要求生成本期或对比期间的门店收入分析 Excel。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> dateFrom = new HashMap<>();
        dateFrom.put("type", "string");
        dateFrom.put("description",
            "本期开始日期，可选；YYYY-MM-DD 或 中文短语 ('本月'/'上月'/'2025-03'/'2025年3月')；省略 = 本月 1 号");
        properties.put("date_from", dateFrom);

        Map<String, Object> dateTo = new HashMap<>();
        dateTo.put("type", "string");
        dateTo.put("description",
            "本期结束日期 (含)，可选；YYYY-MM-DD 或 中文短语；省略 = 本月末");
        properties.put("date_to", dateTo);

        Map<String, Object> storeNames = new HashMap<>();
        storeNames.put("type", "array");
        storeNames.put("items", Map.of("type", "string"));
        storeNames.put("description", "门店名列表 (支持模糊匹配); 省略 = 全部门店");
        properties.put("store_names", storeNames);

        Map<String, Object> mealPeriods = new HashMap<>();
        mealPeriods.put("type", "array");
        mealPeriods.put("items", Map.of("type", "string", "enum", List.of("午市", "晚市")));
        mealPeriods.put(
            "description",
            "班次过滤; 省略 = 全班次。Tool 内化映射: '下午茶' -> '午市', '夜宵' -> '晚市'"
        );
        properties.put("meal_periods", mealPeriods);

        schema.put("properties", properties);
        // Dates are NO LONGER in `required` — Tool resolves Chinese phrases +
        // defaults to current month if missing. This avoids slot-filling Q&A
        // for the most common "生成本月收入管理报表" path.
        schema.put("required", List.<String>of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) throws Exception {

        log.info("生成收入管理报表 - factoryId={}, params={}", factoryId, params);

        // 1. Resolve date_from / date_to from Chinese phrases or ISO; default
        //    to current month if missing/unparseable.
        LocalDate[] range = resolveDateRange(
            (String) params.get("date_from"),
            (String) params.get("date_to")
        );
        String dateFromIso = range[0].toString();
        String dateToIso = range[1].toString();

        // 2. Normalize meal_periods (LLM synonyms → enum). Reject unknowns.
        List<String> rawMealPeriods = (List<String>) params.getOrDefault(
            "meal_periods", Collections.emptyList()
        );
        List<String> mealPeriods;
        try {
            mealPeriods = MealPeriodNormalizer.normalize(rawMealPeriods);
        } catch (IllegalArgumentException e) {
            return buildSimpleResult("班次参数错误: " + e.getMessage(), null);
        }

        // 3. Build Python /prepare request.
        Map<String, Object> request = new HashMap<>();
        request.put("store_names", params.getOrDefault("store_names", Collections.emptyList()));
        request.put("date_from", dateFromIso);
        request.put("date_to", dateToIso);
        request.put("meal_periods", mealPeriods);

        // 3. Call Python.
        String endpoint = "/api/smartbi/" + factoryId + "/revenue-report/prepare";
        Map<String, Object> response = pythonClient.callRevenueReport(
            endpoint,
            request,
            factoryId,
            getUserRole(context)
        );

        if (response == null) {
            return buildSimpleResult("收入管理报表生成失败：Python 服务不可用", null);
        }

        Boolean success = (Boolean) response.get("success");
        if (Boolean.FALSE.equals(success)) {
            String error = (String) response.getOrDefault("error", "未知错误");
            return buildSimpleResult("收入管理报表生成失败: " + error, response);
        }

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) {
            return buildSimpleResult("收入管理报表响应缺少 data 字段", response);
        }
        Map<String, Object> summary = (Map<String, Object>) data.getOrDefault(
            "summary", Collections.emptyMap()
        );

        Object fileSizeRaw = summary.get("file_size_bytes");
        double fileKb = (fileSizeRaw instanceof Number)
            ? ((Number) fileSizeRaw).doubleValue() / 1024.0 : 0;
        boolean cacheHit = Boolean.TRUE.equals(summary.get("cache_hit"));

        String message = String.format(
            "已生成 %s ~ %s 收入管理报表（%s 门店，文件 %.1f KB%s）",
            dateFromIso, dateToIso,
            String.valueOf(summary.getOrDefault("store_count", "?")),
            fileKb,
            cacheHit ? "，缓存命中" : ""
        );
        // Thread the preview data through so Vue AI Chat can render charts
        // inline in the bubble (top 7 stores × 3 blocks).
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("download_url", data.getOrDefault("download_url", ""));
        resultData.put("summary", summary);
        Object preview = data.get("preview");
        if (preview != null) {
            resultData.put("preview", preview);
        }
        return buildSimpleResult(message, resultData);
    }

    // ─── Date resolution helpers ────────────────────────────────────────

    /** Year+month pattern e.g. "2025年3月" / "2025-03" / "2025年03月". */
    private static final Pattern YEAR_MONTH = Pattern.compile(
        "^\\s*(\\d{4})\\s*[年\\-/]\\s*(\\d{1,2})\\s*月?\\s*$"
    );
    /** Standalone ISO date "YYYY-MM-DD". */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Resolve user-supplied date_from/date_to (may be null, ISO, or Chinese phrase)
     * to a concrete LocalDate[2] = {from, to}. Both fields default to current
     * month range when missing/unparseable.
     *
     * <p>Supported phrases:
     * <ul>
     *   <li>{@code "本月"} → current month 1st / last</li>
     *   <li>{@code "上月"} → previous month 1st / last</li>
     *   <li>{@code "2025-03"} / {@code "2025年3月"} → that month's range</li>
     *   <li>{@code "2025-03-01"} → exact date</li>
     * </ul>
     */
    private LocalDate[] resolveDateRange(String fromRaw, String toRaw) {
        LocalDate today = LocalDate.now();
        // Default: current month full range.
        LocalDate defaultStart = today.withDayOfMonth(1);
        LocalDate defaultEnd = today.withDayOfMonth(today.lengthOfMonth());

        // Inputs can be:
        //   - month phrase ("本月"/"上月"/"2025年3月"/"2025-03") → fill BOTH fields
        //   - ISO date → fill ONE field, partner falls back to default
        LocalDate from = null, to = null;

        if (fromRaw != null && !fromRaw.trim().isEmpty()) {
            LocalDate[] phrase = parseMonthPhrase(fromRaw, today);
            if (phrase != null) {
                from = phrase[0];
                if (to == null) to = phrase[1]; // month phrase fills both
            } else {
                from = tryIso(fromRaw);
            }
        }
        if (toRaw != null && !toRaw.trim().isEmpty()) {
            LocalDate[] phrase = parseMonthPhrase(toRaw, today);
            if (phrase != null) {
                to = phrase[1];
                if (from == null) from = phrase[0];
            } else {
                LocalDate iso = tryIso(toRaw);
                if (iso != null) to = iso;
            }
        }
        if (from == null) from = defaultStart;
        if (to == null) to = defaultEnd;
        // Defensive: swap if reversed
        if (to.isBefore(from)) {
            LocalDate tmp = from; from = to; to = tmp;
        }
        return new LocalDate[]{from, to};
    }

    /** Parse "本月"/"上月"/"2025年3月"/"2025-03" → [monthStart, monthEnd]; null if not a month phrase. */
    private LocalDate[] parseMonthPhrase(String raw, LocalDate today) {
        String s = raw.trim();
        if ("本月".equals(s) || "this month".equalsIgnoreCase(s)) {
            return new LocalDate[]{
                today.withDayOfMonth(1),
                today.withDayOfMonth(today.lengthOfMonth()),
            };
        }
        if ("上月".equals(s) || "last month".equalsIgnoreCase(s)) {
            LocalDate prevAnyDay = today.minusMonths(1);
            return new LocalDate[]{
                prevAnyDay.withDayOfMonth(1),
                prevAnyDay.withDayOfMonth(prevAnyDay.lengthOfMonth()),
            };
        }
        Matcher m = YEAR_MONTH.matcher(s);
        if (m.matches()) {
            try {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                LocalDate first = LocalDate.of(year, month, 1);
                return new LocalDate[]{first, first.withDayOfMonth(first.lengthOfMonth())};
            } catch (Exception ignored) {}
        }
        return null;
    }

    private LocalDate tryIso(String raw) {
        try {
            return LocalDate.parse(raw.trim(), ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
