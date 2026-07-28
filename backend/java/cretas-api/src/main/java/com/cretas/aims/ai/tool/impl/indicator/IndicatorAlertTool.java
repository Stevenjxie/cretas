package com.cretas.aims.ai.tool.impl.indicator;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
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
import java.util.Optional;
import java.util.Set;

/**
 * 指标告警 Tool — Sprint 11 D5.
 *
 * <p>AI 工厂 chat 入口: 老板问 "有什么需要关注的" / "现在有几个红灯" / "告警列表" /
 * "厨房有问题吗" 时, LLM 路由到 indicator_alert Tool.
 *
 * <p>扫描工厂全部启用指标的最新快照, 对照 thresholds 评估 breach level, 返回所有
 * 当前处于 RED/YELLOW/WARNING/ALERT 状态的 indicator 列表 (按 severity 倒序).
 *
 * <p><b>Scheduler hook (Sprint 12)</b>: 当 Phase 1 Day 9 IndicatorRecomputeScheduler
 * (PR #172) merge 到 main 后, 应在每次 recompute 完成后调用此 Tool 的内部 scan 方法
 * 触发 Alert. 目前 standalone READ Tool 模式, 老板按需查询.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Sprint 11 D5)
 */
@Slf4j
@Component
public class IndicatorAlertTool extends AbstractBusinessTool {

    @Autowired
    private IndicatorRepository indicatorRepository;

    @Autowired
    private IndicatorVersionRepository versionRepository;

    @Autowired
    private IndicatorThresholdRepository thresholdRepository;

    /** 允许的 severity 过滤值. */
    private static final Set<String> ALLOWED_MIN_SEVERITY =
            Set.of("ALL", "WARNING", "ALERT", "YELLOW", "RED");

    @Override
    public String getToolName() {
        return "indicator_alert";
    }

    /**
     * Override convention-based default — `indicator_alert` 名字含 "_alert" 默认会被
     * {@link AbstractBusinessTool#getActionType()} 推为 NOTIFY, 但本 Tool 实际是 READ
     * (扫描查询当前 breach 状态, 不发送通知). 显式 override.
     */
    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.READ;
    }

    @Override
    public String getDescription() {
        return "扫描工厂所有指标当前告警状态 — 返回处于 RED/YELLOW/WARNING/ALERT 的指标列表 " +
                "(按 severity 倒序). 适用: 老板问 \"现在有几个红灯\" / \"什么需要关注\" / " +
                "\"告警列表\" / \"哪个指标在报警\" / \"现在状态如何\". " +
                "可选 min_severity 过滤 (ALL/WARNING/YELLOW/ALERT/RED), 可选 category 过滤 " +
                "(FACTORY/RESTAURANT/QUALITY 等).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> minSeverity = new HashMap<>();
        minSeverity.put("type", "string");
        minSeverity.put("description", "最低 severity 过滤. ALL (含 GREEN) / WARNING (YELLOW+) / ALERT (RED only)");
        minSeverity.put("enum", List.copyOf(ALLOWED_MIN_SEVERITY));
        minSeverity.put("default", "WARNING");
        properties.put("min_severity", minSeverity);

        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description", "可选: 仅扫描某分类 (FACTORY / RESTAURANT / QUALITY / FINANCE / INVENTORY / SALES / FOOD_SAFETY / AI)");
        properties.put("category", category);

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String minSeverityRaw = getString(params, "min_severity", "WARNING");
        String minSeverity = minSeverityRaw == null ? "WARNING" : minSeverityRaw.trim().toUpperCase();
        if (!ALLOWED_MIN_SEVERITY.contains(minSeverity)) {
            return buildSimpleResult(
                    "min_severity 不合法 (允许: " + ALLOWED_MIN_SEVERITY + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "min_severity",
                            "value", minSeverityRaw));
        }
        int minRank = severityFloor(minSeverity);

        String category = getString(params, "category");
        if (category != null) category = category.trim().toUpperCase();

        log.info("indicator_alert factoryId={} minSeverity={} category={}",
                factoryId, minSeverity, category);

        // 1) Fetch all active indicators (possibly category-filtered)
        List<Indicator> indicators;
        if (category != null && !category.isEmpty()) {
            indicators = indicatorRepository
                    .findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(factoryId, category);
        } else {
            indicators = indicatorRepository.findActiveByFactoryId(factoryId);
        }

        // 2) For each, evaluate breach
        List<Map<String, Object>> breached = new ArrayList<>();
        int totalScanned = indicators.size();
        for (Indicator i : indicators) {
            Optional<IndicatorVersion> latestOpt =
                    versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(i.getId());
            BigDecimal currentValue = latestOpt
                    .map(IndicatorVersion::getValue)
                    .orElse(i.getLastValue());
            if (currentValue == null) continue;

            List<IndicatorThreshold> thresholds =
                    thresholdRepository.findByIndicatorIdAndIsActiveTrue(i.getId());
            String breachLevel = IndicatorBreachEvaluator.evaluate(currentValue, thresholds);
            int rank = IndicatorBreachEvaluator.severityRank(breachLevel);
            if (rank < minRank) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", i.getCode());
            row.put("name", i.getName());
            row.put("category", i.getCategory());
            row.put("unit", i.getUnit());
            row.put("currentValue", currentValue);
            row.put("breachLevel", breachLevel);
            row.put("severityRank", rank);
            latestOpt.ifPresent(v -> row.put("computedAt", v.getComputedAt()));
            breached.add(row);
        }
        breached.sort((a, b) -> Integer.compare(
                (Integer) b.get("severityRank"),
                (Integer) a.get("severityRank")));

        // 3) Stats
        int redCount = (int) breached.stream()
                .filter(r -> "RED".equals(r.get("breachLevel")) || "ALERT".equals(r.get("breachLevel")))
                .count();
        int yellowCount = (int) breached.stream()
                .filter(r -> "YELLOW".equals(r.get("breachLevel")) || "WARNING".equals(r.get("breachLevel")))
                .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("breached", breached);
        data.put("redCount", redCount);
        data.put("yellowCount", yellowCount);
        data.put("totalBreachedCount", breached.size());
        data.put("totalScannedCount", totalScanned);
        data.put("minSeverity", minSeverity);
        if (category != null && !category.isEmpty()) {
            data.put("category", category);
        }

        String summary = buildSummary(redCount, yellowCount, breached.size(), totalScanned, category);
        return buildSimpleResult(summary, data);
    }

    /**
     * minSeverity → severityRank floor. value &gt;= floor 才进结果集.
     */
    private static int severityFloor(String minSeverity) {
        return switch (minSeverity) {
            case "ALL" -> 0;
            case "WARNING", "YELLOW" -> 2;
            case "ALERT", "RED" -> 3;
            default -> 2;
        };
    }

    private static String buildSummary(int redCount, int yellowCount, int totalBreached,
                                       int totalScanned, String category) {
        StringBuilder sb = new StringBuilder();
        if (totalBreached == 0) {
            sb.append("全部正常 — ").append(totalScanned).append(" 个指标无告警");
        } else {
            sb.append("发现 ").append(totalBreached).append(" 个告警");
            if (redCount > 0) {
                sb.append(" — ").append(redCount).append(" 个红灯");
            }
            if (yellowCount > 0) {
                sb.append(redCount > 0 ? " + " : " — ").append(yellowCount).append(" 个黄灯");
            }
        }
        if (category != null && !category.isEmpty()) {
            sb.append(" (").append(category).append(" 分类)");
        }
        return sb.toString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
