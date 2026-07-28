package com.cretas.aims.ai.tool.impl.indicator;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 指标查询工具 — Sprint 11 D3.
 *
 * <p>AI 工厂 chat 通过此 Tool 查询单个指标当前值 + 趋势 + 阈值命中状态。
 * 输入 indicator_code (e.g. AVG_TICKET_PRICE), 输出该指标定义 + 最新快照 +
 * 时间窗内全部 snapshot + breach level (基于 indicator_thresholds 命中)。
 *
 * <p><b>调用链</b>: smart-indicator-query Skill → indicator_query Tool →
 * IndicatorRepository / VersionRepository / ThresholdRepository (直连 Repos, 无 Service 层).
 *
 * <p><b>数据源</b>: Sprint 11 D2 已为 F999_MOCK seed 30天×7=210 行 indicator_versions
 * (mock 数据, 见 docs/sprint-11/data-source-decision.md), Sprint 12 切回 prod F006。
 *
 * <p><b>Threshold 评估</b>: 支持 entity-canonical (GT/GTE/LT/LTE/EQ/BETWEEN) 和
 * mock convention (&gt;/&gt;=/&lt;/&lt;=/=) 两种 operator 写法 — 不同来源数据共存安全。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Sprint 11 D3)
 */
@Slf4j
@Component
public class IndicatorQueryTool extends AbstractBusinessTool {

    @Autowired
    private IndicatorRepository indicatorRepository;

    @Autowired
    private IndicatorVersionRepository versionRepository;

    @Autowired
    private IndicatorThresholdRepository thresholdRepository;

    /** 默认趋势窗口 (天) — 用户未指定 period_start/end 时. */
    private static final int DEFAULT_TREND_DAYS = 30;

    /** 趋势最大返回行数, 防止 LLM context bloat. */
    private static final int MAX_TREND_ROWS = 60;

    @Override
    public String getToolName() {
        return "indicator_query";
    }

    @Override
    public String getDescription() {
        return "查询指定指标的当前值 + 趋势 + 阈值命中状态。" +
                "输入指标编码 (例: AVG_TICKET_PRICE / FACTORY_YIELD_RATE / FOOD_SAFETY_PASS_RATE), " +
                "返回最新快照值 + 计算时间 + 告警级别 + 历史趋势 + 阈值配置。" +
                "适用: 老板问\"今天客单价多少\" / \"良品率怎么样\" / \"食安合不合格\" / \"看一下指标走势\"。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> indicatorCode = new HashMap<>();
        indicatorCode.put("type", "string");
        indicatorCode.put("description",
                "指标编码 (大写下划线), 例: AVG_TICKET_PRICE / TABLE_TURNOVER / " +
                        "RAW_WASTAGE_RATE / FACTORY_YIELD_RATE / FOOD_SAFETY_PASS_RATE / " +
                        "FACTORY_PLAN_ACHIEVE_RATE / DISH_GROSS_MARGIN");
        properties.put("indicator_code", indicatorCode);

        Map<String, Object> periodStart = new HashMap<>();
        periodStart.put("type", "string");
        periodStart.put("format", "date");
        periodStart.put("description", "趋势起始日期 (YYYY-MM-DD), 缺省取 30 天前");
        properties.put("period_start", periodStart);

        Map<String, Object> periodEnd = new HashMap<>();
        periodEnd.put("type", "string");
        periodEnd.put("format", "date");
        periodEnd.put("description", "趋势截止日期 (YYYY-MM-DD), 缺省取今天");
        properties.put("period_end", periodEnd);

        schema.put("properties", properties);
        schema.put("required", List.of("indicator_code"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("indicator_code");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("indicator_code".equals(paramName)) {
            return "请问您想查询哪个指标？可选: 客单价 (AVG_TICKET_PRICE) / 翻台率 (TABLE_TURNOVER) / " +
                    "食材损耗率 (RAW_WASTAGE_RATE) / 良品率 (FACTORY_YIELD_RATE) / " +
                    "食安通过率 (FOOD_SAFETY_PASS_RATE) / 计划达成率 (FACTORY_PLAN_ACHIEVE_RATE) / " +
                    "菜品毛利 (DISH_GROSS_MARGIN)。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String code = getString(params, "indicator_code");
        if (code == null || code.isBlank()) {
            return buildSimpleResult("缺少 indicator_code", null);
        }
        code = code.trim().toUpperCase();

        log.info("indicator_query factoryId={} code={}", factoryId, code);

        // 1) Find indicator definition
        Optional<Indicator> opt =
                indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId);
        if (opt.isEmpty()) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("status", "NOT_FOUND");
            notFound.put("indicatorCode", code);
            notFound.put("hint", "该工厂未配置此指标编码, 请检查 indicators 表或先创建");
            return buildSimpleResult("指标不存在: " + code, notFound);
        }
        Indicator indicator = opt.get();

        // 2) Parse window
        LocalDate periodStart = parseDate(getString(params, "period_start"));
        LocalDate periodEnd = parseDate(getString(params, "period_end"));
        if (periodEnd == null) {
            periodEnd = LocalDate.now();
        }
        if (periodStart == null) {
            periodStart = periodEnd.minusDays(DEFAULT_TREND_DAYS);
        }
        if (periodStart.isAfter(periodEnd)) {
            LocalDate swap = periodStart;
            periodStart = periodEnd;
            periodEnd = swap;
        }

        // 3) Latest version (current value source of truth)
        Optional<IndicatorVersion> latestOpt =
                versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(indicator.getId());

        // 4) Trend in window
        LocalDateTime fromTs = periodStart.atStartOfDay();
        LocalDateTime toTs = periodEnd.atTime(23, 59, 59);
        List<IndicatorVersion> trend =
                versionRepository.findInWindow(indicator.getId(), fromTs, toTs);
        if (trend.size() > MAX_TREND_ROWS) {
            // Down-sample evenly to MAX_TREND_ROWS
            trend = downsample(trend, MAX_TREND_ROWS);
        }

        // 5) Thresholds + breach evaluation
        List<IndicatorThreshold> thresholds =
                thresholdRepository.findByIndicatorIdAndIsActiveTrue(indicator.getId());
        BigDecimal currentValue = latestOpt
                .map(IndicatorVersion::getValue)
                .orElse(indicator.getLastValue());
        String breachLevel = evaluateBreachLevel(currentValue, thresholds);

        // 6) Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indicator", serializeIndicator(indicator));
        result.put("currentValue", currentValue);
        result.put("breachLevel", breachLevel);
        latestOpt.ifPresent(v -> {
            result.put("latestComputedAt", v.getComputedAt());
            result.put("latestPeriodStart", v.getPeriodStart());
            result.put("latestPeriodEnd", v.getPeriodEnd());
            result.put("latestAlertLevel", v.getAlertLevel());
        });
        result.put("thresholds", serializeThresholds(thresholds));
        result.put("trend", serializeTrend(trend));
        result.put("trendSize", trend.size());
        result.put("periodStart", periodStart);
        result.put("periodEnd", periodEnd);

        String summary = buildSummaryText(indicator, currentValue, breachLevel, trend.size());
        return buildSimpleResult(summary, result);
    }

    // ============================================================
    // Threshold breach evaluation
    // ============================================================

    /**
     * 评估当前值 V 是否命中任一 threshold, 返回命中级别 (按 severity 优先级).
     *
     * <p>支持两种 operator 写法:
     * <ul>
     *   <li>Entity-canonical: GT / GTE / LT / LTE / EQ / BETWEEN</li>
     *   <li>Mock convention: &gt; / &gt;= / &lt; / &lt;= / =</li>
     * </ul>
     *
     * <p>Severity 排序 (高 → 低): RED &gt; ALERT &gt; YELLOW &gt; WARNING &gt; GREEN.
     * 一旦匹配到最高 severity, 立即返回.
     */
    String evaluateBreachLevel(BigDecimal value, List<IndicatorThreshold> thresholds) {
        if (value == null || thresholds == null || thresholds.isEmpty()) {
            return null;
        }
        List<IndicatorThreshold> sorted = new ArrayList<>(thresholds);
        sorted.sort((a, b) -> Integer.compare(
                severityRank(b.getAlertLevel()),
                severityRank(a.getAlertLevel())));
        for (IndicatorThreshold t : sorted) {
            if (matches(value, t)) {
                return t.getAlertLevel();
            }
        }
        return "GREEN";
    }

    private static int severityRank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase()) {
            case "RED", "ALERT" -> 3;
            case "YELLOW", "WARNING" -> 2;
            case "GREEN", "OK" -> 1;
            default -> 0;
        };
    }

    private static boolean matches(BigDecimal value, IndicatorThreshold t) {
        BigDecimal threshold = t.getThresholdValue();
        if (threshold == null) return false;
        String op = t.getOperator() == null ? "" : t.getOperator().trim().toUpperCase();
        int cmp = value.compareTo(threshold);
        return switch (op) {
            case "GT", ">" -> cmp > 0;
            case "GTE", ">=", "≥" -> cmp >= 0;
            case "LT", "<" -> cmp < 0;
            case "LTE", "<=", "≤" -> cmp <= 0;
            case "EQ", "=", "==" -> cmp == 0;
            case "BETWEEN" -> {
                BigDecimal upper = t.getThresholdValueUpper();
                if (upper == null) yield false;
                yield cmp >= 0 && value.compareTo(upper) <= 0;
            }
            default -> false;
        };
    }

    // ============================================================
    // Serialization helpers
    // ============================================================

    private Map<String, Object> serializeIndicator(Indicator i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("code", i.getCode());
        m.put("name", i.getName());
        m.put("category", i.getCategory());
        m.put("unit", i.getUnit());
        m.put("description", i.getDescription());
        m.put("computeStrategy", i.getComputeStrategy());
        return m;
    }

    private List<Map<String, Object>> serializeThresholds(List<IndicatorThreshold> thresholds) {
        List<Map<String, Object>> rows = new ArrayList<>(thresholds.size());
        for (IndicatorThreshold t : thresholds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertLevel", t.getAlertLevel());
            m.put("operator", t.getOperator());
            m.put("value", t.getThresholdValue());
            m.put("valueUpper", t.getThresholdValueUpper());
            rows.add(m);
        }
        return rows;
    }

    private List<Map<String, Object>> serializeTrend(List<IndicatorVersion> versions) {
        List<Map<String, Object>> rows = new ArrayList<>(versions.size());
        for (IndicatorVersion v : versions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("periodStart", v.getPeriodStart());
            m.put("periodEnd", v.getPeriodEnd());
            m.put("value", v.getValue());
            m.put("alertLevel", v.getAlertLevel());
            m.put("computedAt", v.getComputedAt());
            rows.add(m);
        }
        return rows;
    }

    /**
     * 均匀降采样 — N 行 → maxRows 行, 保留首尾, 中间按等步长抽取.
     */
    static <T> List<T> downsample(List<T> rows, int maxRows) {
        int n = rows.size();
        if (n <= maxRows) return rows;
        List<T> out = new ArrayList<>(maxRows);
        double step = (double) (n - 1) / (maxRows - 1);
        for (int i = 0; i < maxRows; i++) {
            int idx = (int) Math.round(i * step);
            out.add(rows.get(Math.min(idx, n - 1)));
        }
        return out;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String buildSummaryText(Indicator indicator, BigDecimal value,
                                           String breachLevel, int trendSize) {
        StringBuilder sb = new StringBuilder();
        sb.append(indicator.getName()).append(" 当前 ");
        if (value == null) {
            sb.append("暂无数据");
        } else {
            sb.append(value.stripTrailingZeros().toPlainString());
            if (indicator.getUnit() != null && !indicator.getUnit().isBlank()) {
                sb.append(" ").append(indicator.getUnit());
            }
        }
        if (breachLevel != null) {
            sb.append(" (状态: ").append(breachLevel).append(")");
        }
        sb.append(", 趋势含 ").append(trendSize).append(" 个数据点");
        return sb.toString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
