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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 指标对比 Tool — Sprint 11 D4.
 *
 * <p>AI 工厂 chat 入口: 老板问 "客单价和翻台率哪个有问题" / "良品率 vs 食安通过率对比" /
 * "把 3 个指标一起看" 时, LLM 路由到 indicator_comparison Tool.
 *
 * <p>输入 indicator_codes (1-10 个 code), 返回每个 indicator 的 currentValue +
 * breachLevel + thresholds, 并标识哪些指标 worst-state (告警最严重). 适合作 dashboard
 * one-pager.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Sprint 11 D4)
 */
@Slf4j
@Component
public class IndicatorComparisonTool extends AbstractBusinessTool {

    @Autowired
    private IndicatorRepository indicatorRepository;

    @Autowired
    private IndicatorVersionRepository versionRepository;

    @Autowired
    private IndicatorThresholdRepository thresholdRepository;

    /** indicator_codes 最多对比的数量 — context bloat 防护. */
    private static final int MAX_INDICATORS = 10;

    @Override
    public String getToolName() {
        return "indicator_comparison";
    }

    @Override
    public String getDescription() {
        return "对比多个指标 — 输入 indicator_codes 列表 (1-10 个), " +
                "返回每个指标当前值 + 告警级别 + worst-state 标识. " +
                "适用: 老板问 \"客单价和翻台率哪个有问题\" / \"把这几个一起看\" / " +
                "\"指标对比\" / \"3 个指标摆一起\" 时.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> codes = new HashMap<>();
        codes.put("type", "array");
        Map<String, Object> codeItem = new HashMap<>();
        codeItem.put("type", "string");
        codes.put("items", codeItem);
        codes.put("description", "1-10 个指标编码, 例: " +
                "[\"AVG_TICKET_PRICE\", \"TABLE_TURNOVER\", \"FOOD_SAFETY_PASS_RATE\"]");
        codes.put("minItems", 1);
        codes.put("maxItems", MAX_INDICATORS);
        properties.put("indicator_codes", codes);

        schema.put("properties", properties);
        schema.put("required", List.of("indicator_codes"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("indicator_codes");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("indicator_codes".equals(paramName)) {
            return "请提供要对比的指标编码列表, 例如 [AVG_TICKET_PRICE, FACTORY_YIELD_RATE]. " +
                    "可选: 客单价 / 翻台率 / 食材损耗率 / 良品率 / 食安通过率 / 计划达成率 / 菜品毛利.";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        List<String> codes = normalizeCodes(params.get("indicator_codes"));
        if (codes.isEmpty()) {
            return buildSimpleResult("indicator_codes 为空",
                    Map.of("status", "VALIDATION_ERROR", "field", "indicator_codes"));
        }
        if (codes.size() > MAX_INDICATORS) {
            return buildSimpleResult(
                    "indicator_codes 最多 " + MAX_INDICATORS + " 个 (收到 " + codes.size() + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "indicator_codes",
                            "limit", MAX_INDICATORS));
        }

        log.info("indicator_comparison factoryId={} codes={}", factoryId, codes);

        List<Map<String, Object>> rows = new ArrayList<>(codes.size());
        List<String> notFound = new ArrayList<>();
        int worstRank = -1;
        String worstCode = null;

        for (String code : codes) {
            Optional<Indicator> opt =
                    indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId);
            if (opt.isEmpty()) {
                notFound.add(code);
                continue;
            }
            Indicator i = opt.get();
            Optional<IndicatorVersion> latestOpt =
                    versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(i.getId());
            BigDecimal currentValue = latestOpt
                    .map(IndicatorVersion::getValue)
                    .orElse(i.getLastValue());
            List<IndicatorThreshold> thresholds =
                    thresholdRepository.findByIndicatorIdAndIsActiveTrue(i.getId());
            String breachLevel = IndicatorBreachEvaluator.evaluate(currentValue, thresholds);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", i.getCode());
            row.put("name", i.getName());
            row.put("category", i.getCategory());
            row.put("unit", i.getUnit());
            row.put("currentValue", currentValue);
            row.put("breachLevel", breachLevel);
            latestOpt.ifPresent(v -> row.put("computedAt", v.getComputedAt()));
            row.put("thresholdsCount", thresholds.size());
            rows.add(row);

            int rank = IndicatorBreachEvaluator.severityRank(breachLevel);
            if (rank > worstRank) {
                worstRank = rank;
                worstCode = i.getCode();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("found", rows.size());
        data.put("requested", codes.size());
        if (!notFound.isEmpty()) {
            data.put("notFound", notFound);
        }
        data.put("worstIndicator", worstCode);
        data.put("worstSeverity", worstRank >= 0 ? severityName(worstRank) : null);

        String summary = buildSummary(rows.size(), codes.size(),
                worstCode, worstRank, notFound);
        return buildSimpleResult(summary, data);
    }

    private static List<String> normalizeCodes(Object raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = Objects.toString(o).trim().toUpperCase();
                if (!s.isEmpty() && !out.contains(s)) {
                    out.add(s);
                }
            }
        } else if (raw instanceof String s) {
            for (String token : s.split(",")) {
                String t = token.trim().toUpperCase();
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String severityName(int rank) {
        return switch (rank) {
            case 3 -> "RED/ALERT";
            case 2 -> "YELLOW/WARNING";
            case 1 -> "GREEN/OK";
            default -> null;
        };
    }

    private static String buildSummary(int found, int requested, String worstCode,
                                       int worstRank, List<String> notFound) {
        StringBuilder sb = new StringBuilder();
        sb.append("对比 ").append(found).append("/").append(requested).append(" 个指标");
        if (!notFound.isEmpty()) {
            sb.append(" (未找到: ").append(String.join(", ", notFound)).append(")");
        }
        if (worstCode != null && worstRank >= 2) {
            sb.append(" — 最严重: ").append(worstCode)
                    .append(" (").append(severityName(worstRank)).append(")");
        } else if (worstCode != null) {
            sb.append(" — 全部正常");
        }
        return sb.toString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
