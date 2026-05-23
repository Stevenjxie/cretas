package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.AIInsight;
import com.cretas.aims.dto.smartbi.ChartConfig;
import com.cretas.aims.dto.smartbi.DashboardResponse;
import com.cretas.aims.dto.smartbi.KPICard;
import com.cretas.aims.dto.smartbi.MetricResult;
import com.cretas.aims.dto.smartbi.RankingItem;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.smartbi.QualityAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 质量分析服务实现
 *
 * 实现 SmartBI 系统中质量相关的分析，包括：
 * - 质量概览：FPY、不良率、返工/报废成本
 * - 不良分析：不良类型分布、帕累托分析
 * - 成本分析：返工成本、报废成本、质量成本占比
 * - 趋势分析：日/周/月质量指标趋势
 * - 产线分析：各产线质量对比
 *
 * 关键质量指标计算公式:
 * - FPY = First Pass Count / Total Inspections
 * - Defect Rate = Defect Count / Total Inspections
 * - Rework Rate = Rework Count / Defect Count
 * - Scrap Rate = Scrap Count / Defect Count
 *
 * 所有计算使用 BigDecimal 确保精度，默认保留 2 位小数。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityAnalysisServiceImpl implements QualityAnalysisService {

    /** Canvas-Thresholds resolver (Phase A P0-3) — overlays FALLBACK_* defaults below
     * with per-factory configured values from {@code factory_thresholds} table. */
    @Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    // 计算精度配置
    private static final int SCALE = 4;
    private static final int DISPLAY_SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    // ==================== 预警阈值 fallback 默认值 ====================
    // 通过 thresholdResolver.getBigDecimal(factoryId, KEY, FALLBACK) 读取，仅 DB row 不存在时使用 fallback.

    // FPY 预警阈值 fallback
    private static final BigDecimal FALLBACK_FPY_RED_THRESHOLD = new BigDecimal("95");
    private static final BigDecimal FALLBACK_FPY_YELLOW_THRESHOLD = new BigDecimal("98");

    // 不良率预警阈值 fallback
    private static final BigDecimal FALLBACK_DEFECT_RATE_RED_THRESHOLD = new BigDecimal("5");
    private static final BigDecimal FALLBACK_DEFECT_RATE_YELLOW_THRESHOLD = new BigDecimal("2");

    // 质量成本率预警阈值 fallback
    private static final BigDecimal FALLBACK_QUALITY_COST_RED_THRESHOLD = new BigDecimal("3");
    private static final BigDecimal FALLBACK_QUALITY_COST_YELLOW_THRESHOLD = new BigDecimal("1.5");

    // 返工率预警阈值 fallback
    private static final BigDecimal FALLBACK_REWORK_RATE_RED_THRESHOLD = new BigDecimal("20");
    private static final BigDecimal FALLBACK_REWORK_RATE_YELLOW_THRESHOLD = new BigDecimal("10");

    // ==================== Threshold resolver shortcuts ====================

    private BigDecimal resolveFpyRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_FPY_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_FPY_RED, FALLBACK_FPY_RED_THRESHOLD);
    }

    private BigDecimal resolveFpyYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_FPY_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_FPY_YELLOW, FALLBACK_FPY_YELLOW_THRESHOLD);
    }

    private BigDecimal resolveDefectRateRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_DEFECT_RATE_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_DEFECT_RATE_RED, FALLBACK_DEFECT_RATE_RED_THRESHOLD);
    }

    private BigDecimal resolveDefectRateYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_DEFECT_RATE_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_DEFECT_RATE_YELLOW, FALLBACK_DEFECT_RATE_YELLOW_THRESHOLD);
    }

    @SuppressWarnings("unused")  // wired for Canvas Thresholds Hub (Phase A); future quality-cost insight path will consume
    private BigDecimal resolveQualityCostRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_QUALITY_COST_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_COST_RED, FALLBACK_QUALITY_COST_RED_THRESHOLD);
    }

    @SuppressWarnings("unused")  // wired for Canvas Thresholds Hub (Phase A); future quality-cost insight path will consume
    private BigDecimal resolveQualityCostYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_QUALITY_COST_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_COST_YELLOW, FALLBACK_QUALITY_COST_YELLOW_THRESHOLD);
    }

    private BigDecimal resolveReworkRateRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_REWORK_RATE_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_REWORK_RATE_RED, FALLBACK_REWORK_RATE_RED_THRESHOLD);
    }

    private BigDecimal resolveReworkRateYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_REWORK_RATE_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.QUALITY_REWORK_RATE_YELLOW, FALLBACK_REWORK_RATE_YELLOW_THRESHOLD);
    }

    // ==================== 质量概览 ====================

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getQualitySummary(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取质量概览: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // 获取质量数据（使用模拟数据）
        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);

        if (qualityData.isEmpty()) {
            log.warn("未找到质量数据: factoryId={}", factoryId);
            return buildEmptyDashboard();
        }

        // 计算 KPI 卡片
        List<MetricResult> metricResults = calculateQualityKpiCards(qualityData, factoryId, startDate, endDate);
        List<KPICard> kpiCards = convertToKPICards(metricResults);

        // 生成图表
        Map<String, ChartConfig> charts = new LinkedHashMap<>();
        charts.put("quality_trend", buildQualityTrendChartFromData(qualityData, "DAY"));
        charts.put("defect_pareto", buildDefectParetoFromData(qualityData));
        charts.put("quality_cost_distribution", buildQualityCostDistributionFromData(qualityData));
        charts.put("product_line_quality", buildProductLineQualityComparisonFromData(qualityData));

        // 生成排名
        Map<String, List<RankingItem>> rankings = new LinkedHashMap<>();
        rankings.put("defect_type", calculateDefectTypeRankingFromData(qualityData));
        rankings.put("product_line", calculateProductLineQualityRankingFromData(factoryId, qualityData));

        // 生成 AI 洞察
        List<AIInsight> aiInsights = generateQualityInsights(factoryId, qualityData, metricResults);

        // 生成建议
        List<String> suggestions = generateQualitySuggestions(factoryId, qualityData, metricResults);

        return DashboardResponse.builder()
                .period("CUSTOM")
                .startDate(startDate)
                .endDate(endDate)
                .kpiCards(kpiCards)
                .charts(charts)
                .rankings(rankings)
                .aiInsights(aiInsights)
                .suggestions(suggestions)
                .generatedAt(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 不良分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getDefectAnalysis(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取不良分析: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);

        if (qualityData.isEmpty()) {
            return Collections.emptyList();
        }

        List<MetricResult> metrics = new ArrayList<>();

        // 总检验数
        long totalInspections = qualityData.stream()
                .mapToLong(d -> toLong(d.get("totalInspections")))
                .sum();
        metrics.add(MetricResult.of(TOTAL_INSPECTIONS, "总检验数", new BigDecimal(totalInspections), "件"));

        // 不良品数
        long defectCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("defectCount")))
                .sum();
        metrics.add(MetricResult.of(DEFECT_COUNT, "不良品数", new BigDecimal(defectCount), "件"));

        // 不良率
        BigDecimal defectRate = totalInspections > 0
                ? new BigDecimal(defectCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.builder()
                .metricCode(DEFECT_RATE)
                .metricName("不良率")
                .value(defectRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.2f%%", defectRate.doubleValue()))
                .unit("%")
                .alertLevel(determineDefectRateAlertLevel(factoryId, defectRate))
                .build());

        // 按不良类型统计
        Map<String, Long> defectsByType = qualityData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("defectType"),
                        Collectors.summingLong(d -> toLong(d.get("defectCount")))
                ));

        // 添加各不良类型的指标
        for (Map.Entry<String, Long> entry : defectsByType.entrySet()) {
            BigDecimal typeRatio = defectCount > 0
                    ? new BigDecimal(entry.getValue()).divide(new BigDecimal(defectCount), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            metrics.add(MetricResult.builder()
                    .metricCode("DEFECT_TYPE_" + entry.getKey().toUpperCase().replace(" ", "_"))
                    .metricName(entry.getKey() + "占比")
                    .value(typeRatio.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .formattedValue(String.format("%.1f%%", typeRatio.doubleValue()))
                    .unit("%")
                    .dimensionValue(entry.getKey())
                    .alertLevel(MetricResult.AlertLevel.GREEN.name())
                    .build());
        }

        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getDefectTypeRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取不良类型排名: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);
        return calculateDefectTypeRankingFromData(qualityData);
    }

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getDefectParetoChart(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取不良帕累托图表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);
        return buildDefectParetoFromData(qualityData);
    }

    // ==================== 返工/报废成本分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getReworkCost(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取返工成本指标: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);

        if (qualityData.isEmpty()) {
            return Collections.emptyList();
        }

        List<MetricResult> metrics = new ArrayList<>();

        // 返工数
        long reworkCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("reworkCount")))
                .sum();
        metrics.add(MetricResult.of(REWORK_COUNT, "返工数", new BigDecimal(reworkCount), "件"));

        // 返工成本
        BigDecimal reworkCost = sumField(qualityData, "reworkCost");
        metrics.add(MetricResult.builder()
                .metricCode(REWORK_COST)
                .metricName("返工成本")
                .value(reworkCost.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(reworkCost))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 报废数
        long scrapCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("scrapCount")))
                .sum();
        metrics.add(MetricResult.of(SCRAP_COUNT, "报废数", new BigDecimal(scrapCount), "件"));

        // 报废成本
        BigDecimal scrapCost = sumField(qualityData, "scrapCost");
        metrics.add(MetricResult.builder()
                .metricCode(SCRAP_COST)
                .metricName("报废成本")
                .value(scrapCost.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(scrapCost))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 质量成本总计
        BigDecimal totalQualityCost = reworkCost.add(scrapCost);
        metrics.add(MetricResult.builder()
                .metricCode(TOTAL_QUALITY_COST)
                .metricName("质量成本总计")
                .value(totalQualityCost.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(totalQualityCost))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 返工率（返工数/不良数）
        long defectCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("defectCount")))
                .sum();
        BigDecimal reworkRate = defectCount > 0
                ? new BigDecimal(reworkCount).divide(new BigDecimal(defectCount), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.builder()
                .metricCode(REWORK_RATE)
                .metricName("返工率")
                .value(reworkRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", reworkRate.doubleValue()))
                .unit("%")
                .alertLevel(determineReworkRateAlertLevel(factoryId, reworkRate))
                .description("返工数 / 不良总数")
                .build());

        // 报废率（报废数/不良数）
        BigDecimal scrapRate = defectCount > 0
                ? new BigDecimal(scrapCount).divide(new BigDecimal(defectCount), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.builder()
                .metricCode(SCRAP_RATE)
                .metricName("报废率")
                .value(scrapRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", scrapRate.doubleValue()))
                .unit("%")
                .alertLevel(scrapRate.compareTo(new BigDecimal("30")) > 0
                        ? MetricResult.AlertLevel.RED.name()
                        : MetricResult.AlertLevel.GREEN.name())
                .description("报废数 / 不良总数")
                .build());

        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getQualityCostDistributionChart(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取质量成本分布图表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);
        return buildQualityCostDistributionFromData(qualityData);
    }

    // ==================== 质量趋势分析 ====================

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getQualityTrendChart(String factoryId, LocalDate startDate, LocalDate endDate, String period) {
        log.info("获取质量趋势图表: factoryId={}, startDate={}, endDate={}, period={}",
                factoryId, startDate, endDate, period);

        List<Map<String, Object>> qualityData = generateMockQualityData(factoryId, startDate, endDate);
        return buildQualityTrendChartFromData(qualityData, period);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 生成模拟质量数据
     * 实际实现时应从 QualityInspection、ReworkRecord、DisposalRecord 实体查询
     */
    private List<Map<String, Object>> generateMockQualityData(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> data = new ArrayList<>();
        Random random = new Random(factoryId.hashCode());

        String[] productionLines = {"产线A", "产线B", "产线C", "产线D"};
        String[] defectTypes = {"外观缺陷", "尺寸偏差", "功能故障", "材料缺陷", "装配不良"};
        String[] products = {"产品A", "产品B", "产品C"};

        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);

        for (int i = 0; i <= daysBetween; i++) {
            LocalDate date = startDate.plusDays(i);

            for (String line : productionLines) {
                for (String product : products) {
                    Map<String, Object> record = new LinkedHashMap<>();

                    record.put("factoryId", factoryId);
                    record.put("date", date);
                    record.put("productionLine", line);
                    record.put("product", product);

                    // 总检验数
                    int totalInspections = 100 + random.nextInt(200);
                    record.put("totalInspections", totalInspections);

                    // 不良数 (2-8% 的不良率)
                    int defectCount = (int) (totalInspections * (0.02 + random.nextDouble() * 0.06));
                    record.put("defectCount", defectCount);

                    // 首次通过数
                    int firstPassCount = totalInspections - defectCount;
                    record.put("firstPassCount", firstPassCount);

                    // 不良类型
                    String defectType = defectTypes[random.nextInt(defectTypes.length)];
                    record.put("defectType", defectType);

                    // 返工数 (不良品的 60-80%)
                    int reworkCount = (int) (defectCount * (0.6 + random.nextDouble() * 0.2));
                    record.put("reworkCount", reworkCount);

                    // 报废数 (剩余的不良品)
                    int scrapCount = defectCount - reworkCount;
                    record.put("scrapCount", scrapCount);

                    // 返工成本 (每件 10-30 元)
                    BigDecimal reworkCost = new BigDecimal(reworkCount * (10 + random.nextInt(20)));
                    record.put("reworkCost", reworkCost);

                    // 报废成本 (每件 50-150 元)
                    BigDecimal scrapCost = new BigDecimal(scrapCount * (50 + random.nextInt(100)));
                    record.put("scrapCost", scrapCost);

                    // 客户投诉数
                    int complaintCount = random.nextInt(3);
                    record.put("complaintCount", complaintCount);

                    // 退货数
                    int returnCount = random.nextInt(5);
                    record.put("returnCount", returnCount);

                    data.add(record);
                }
            }
        }

        return data;
    }

    /**
     * 计算质量 KPI 卡片
     */
    private List<MetricResult> calculateQualityKpiCards(List<Map<String, Object>> qualityData,
                                                         String factoryId,
                                                         LocalDate startDate,
                                                         LocalDate endDate) {
        List<MetricResult> kpiCards = new ArrayList<>();

        // 计算 FPY
        long totalInspections = qualityData.stream()
                .mapToLong(d -> toLong(d.get("totalInspections")))
                .sum();
        long firstPassCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("firstPassCount")))
                .sum();
        BigDecimal fpy = totalInspections > 0
                ? new BigDecimal(firstPassCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        kpiCards.add(MetricResult.builder()
                .metricCode(FPY)
                .metricName("首次通过率 (FPY)")
                .value(fpy.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.2f%%", fpy.doubleValue()))
                .unit("%")
                .alertLevel(determineFPYAlertLevel(factoryId, fpy))
                .description("首次检验通过数 / 总检验数")
                .build());

        // 计算不良率
        long defectCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("defectCount")))
                .sum();
        BigDecimal defectRate = totalInspections > 0
                ? new BigDecimal(defectCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        kpiCards.add(MetricResult.builder()
                .metricCode(DEFECT_RATE)
                .metricName("不良率")
                .value(defectRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.2f%%", defectRate.doubleValue()))
                .unit("%")
                .alertLevel(determineDefectRateAlertLevel(factoryId, defectRate))
                .description("不良品数 / 总检验数")
                .build());

        // 返工成本
        BigDecimal reworkCost = sumField(qualityData, "reworkCost");
        kpiCards.add(MetricResult.builder()
                .metricCode(REWORK_COST)
                .metricName("返工成本")
                .value(reworkCost.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(reworkCost))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 报废成本
        BigDecimal scrapCost = sumField(qualityData, "scrapCost");
        kpiCards.add(MetricResult.builder()
                .metricCode(SCRAP_COST)
                .metricName("报废成本")
                .value(scrapCost.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(scrapCost))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 质量成本总计
        BigDecimal totalQualityCost = reworkCost.add(scrapCost);
        kpiCards.add(MetricResult.builder()
                .metricCode(TOTAL_QUALITY_COST)
                .metricName("质量成本总计")
                .value(totalQualityCost.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(totalQualityCost))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 客户投诉数
        long complaintCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("complaintCount")))
                .sum();
        kpiCards.add(MetricResult.builder()
                .metricCode(CUSTOMER_COMPLAINT_COUNT)
                .metricName("客户投诉数")
                .value(new BigDecimal(complaintCount))
                .formattedValue(String.format("%d 件", complaintCount))
                .unit("件")
                .alertLevel(complaintCount > 10 ? MetricResult.AlertLevel.RED.name() :
                        (complaintCount > 5 ? MetricResult.AlertLevel.YELLOW.name() : MetricResult.AlertLevel.GREEN.name()))
                .build());

        return kpiCards;
    }

    /**
     * 将 MetricResult 列表转换为 KPICard 列表
     */
    private List<KPICard> convertToKPICards(List<MetricResult> metricResults) {
        List<KPICard> kpiCards = new ArrayList<>();
        for (MetricResult metric : metricResults) {
            String status = "green";
            if (metric.getAlertLevel() != null) {
                switch (metric.getAlertLevel()) {
                    case "RED":
                        status = "red";
                        break;
                    case "YELLOW":
                        status = "yellow";
                        break;
                    default:
                        status = "green";
                }
            }

            String trend = "flat";
            if (metric.getChangeDirection() != null) {
                switch (metric.getChangeDirection()) {
                    case "UP":
                        trend = "up";
                        break;
                    case "DOWN":
                        trend = "down";
                        break;
                    default:
                        trend = "flat";
                }
            }

            kpiCards.add(KPICard.builder()
                    .key(metric.getMetricCode())
                    .title(metric.getMetricName())
                    .rawValue(metric.getValue())
                    .value(metric.getFormattedValue() != null ? metric.getFormattedValue() :
                           (metric.getValue() != null ? metric.getValue().toString() : "-"))
                    .unit(metric.getUnit())
                    .changeRate(metric.getChangePercent())
                    .change(metric.getChangeValue())
                    .trend(trend)
                    .status(status)
                    .description(metric.getDescription())
                    .build());
        }
        return kpiCards;
    }

    /**
     * 计算不良类型排名（帕累托分析）
     */
    private List<RankingItem> calculateDefectTypeRankingFromData(List<Map<String, Object>> qualityData) {
        Map<String, Long> defectsByType = qualityData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("defectType"),
                        Collectors.summingLong(d -> toLong(d.get("defectCount")))
                ));

        long totalDefects = defectsByType.values().stream().mapToLong(Long::longValue).sum();

        List<RankingItem> rankings = defectsByType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    BigDecimal percentage = totalDefects > 0
                            ? new BigDecimal(entry.getValue()).divide(new BigDecimal(totalDefects), SCALE, ROUNDING_MODE)
                                    .multiply(new BigDecimal("100"))
                            : BigDecimal.ZERO;

                    return RankingItem.builder()
                            .name(entry.getKey())
                            .value(new BigDecimal(entry.getValue()))
                            .completionRate(percentage.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                            .alertLevel(MetricResult.AlertLevel.GREEN.name())
                            .build();
                })
                .collect(Collectors.toList());

        // 设置排名和累计占比
        BigDecimal cumulativePercentage = BigDecimal.ZERO;
        for (int i = 0; i < rankings.size(); i++) {
            rankings.get(i).setRank(i + 1);
            cumulativePercentage = cumulativePercentage.add(rankings.get(i).getCompletionRate());
            // 80-20 法则：如果累计超过 80%，标记为主要问题
            if (cumulativePercentage.compareTo(new BigDecimal("80")) <= 0) {
                rankings.get(i).setAlertLevel(MetricResult.AlertLevel.RED.name());
            }
        }

        return rankings;
    }

    /**
     * 计算产线质量排名
     */
    private List<RankingItem> calculateProductLineQualityRankingFromData(String factoryId, List<Map<String, Object>> qualityData) {
        Map<String, List<Map<String, Object>>> groupedByLine = qualityData.stream()
                .collect(Collectors.groupingBy(d -> (String) d.get("productionLine")));

        List<RankingItem> rankings = new ArrayList<>();
        BigDecimal fpyYellow = resolveFpyYellowThreshold(factoryId);

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByLine.entrySet()) {
            String lineName = entry.getKey();
            List<Map<String, Object>> lineData = entry.getValue();

            // 计算该产线的 FPY
            long totalInspections = lineData.stream()
                    .mapToLong(d -> toLong(d.get("totalInspections")))
                    .sum();
            long firstPassCount = lineData.stream()
                    .mapToLong(d -> toLong(d.get("firstPassCount")))
                    .sum();
            BigDecimal fpy = totalInspections > 0
                    ? new BigDecimal(firstPassCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            rankings.add(RankingItem.builder()
                    .name(lineName)
                    .value(fpy.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .target(fpyYellow)
                    .completionRate(fpy.divide(fpyYellow, SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100")).setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(determineFPYAlertLevel(factoryId, fpy))
                    .build());
        }

        // 按 FPY 降序排序并设置排名
        rankings.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (int i = 0; i < rankings.size(); i++) {
            rankings.get(i).setRank(i + 1);
        }

        return rankings;
    }

    /**
     * 构建质量趋势图表
     */
    private ChartConfig buildQualityTrendChartFromData(List<Map<String, Object>> qualityData, String period) {
        Map<String, List<Map<String, Object>>> groupedByDate;

        switch (period.toUpperCase()) {
            case "WEEK":
                groupedByDate = aggregateByWeek(qualityData);
                break;
            case "MONTH":
                groupedByDate = aggregateByMonth(qualityData);
                break;
            case "DAY":
            default:
                groupedByDate = aggregateByDay(qualityData);
                break;
        }

        List<Map<String, Object>> chartData = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByDate.entrySet()
                .stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toList())) {

            String dateKey = entry.getKey();
            List<Map<String, Object>> dayData = entry.getValue();

            long totalInspections = dayData.stream()
                    .mapToLong(d -> toLong(d.get("totalInspections")))
                    .sum();
            long firstPassCount = dayData.stream()
                    .mapToLong(d -> toLong(d.get("firstPassCount")))
                    .sum();
            long defectCount = dayData.stream()
                    .mapToLong(d -> toLong(d.get("defectCount")))
                    .sum();

            BigDecimal fpy = totalInspections > 0
                    ? new BigDecimal(firstPassCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
            BigDecimal defectRate = totalInspections > 0
                    ? new BigDecimal(defectCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            Map<String, Object> dataPoint = new LinkedHashMap<>();
            dataPoint.put("date", dateKey);
            dataPoint.put("fpy", fpy.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("defectRate", defectRate.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("totalInspections", totalInspections);

            chartData.add(dataPoint);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showLegend", true);
        options.put("multiLine", true);
        options.put("yAxisMax", 100);

        return ChartConfig.builder()
                .chartType("LINE")
                .title("质量趋势")
                .xAxisField("date")
                .yAxisField("fpy")
                .seriesField("metric")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 构建不良帕累托图表
     */
    private ChartConfig buildDefectParetoFromData(List<Map<String, Object>> qualityData) {
        Map<String, Long> defectsByType = qualityData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("defectType"),
                        Collectors.summingLong(d -> toLong(d.get("defectCount")))
                ));

        long totalDefects = defectsByType.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> chartData = new ArrayList<>();
        BigDecimal cumulativePercentage = BigDecimal.ZERO;

        List<Map.Entry<String, Long>> sortedDefects = defectsByType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());

        for (Map.Entry<String, Long> entry : sortedDefects) {
            BigDecimal percentage = totalDefects > 0
                    ? new BigDecimal(entry.getValue()).divide(new BigDecimal(totalDefects), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
            cumulativePercentage = cumulativePercentage.add(percentage);

            Map<String, Object> dataPoint = new LinkedHashMap<>();
            dataPoint.put("defectType", entry.getKey());
            dataPoint.put("count", entry.getValue());
            dataPoint.put("percentage", percentage.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("cumulative", cumulativePercentage.setScale(DISPLAY_SCALE, ROUNDING_MODE));

            chartData.add(dataPoint);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showCumulativeLine", true);
        options.put("showPercentage", true);

        return ChartConfig.builder()
                .chartType("BAR")
                .title("不良类型帕累托分析")
                .xAxisField("defectType")
                .yAxisField("count")
                .seriesField("metric")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 构建质量成本分布图表
     */
    private ChartConfig buildQualityCostDistributionFromData(List<Map<String, Object>> qualityData) {
        BigDecimal reworkCost = sumField(qualityData, "reworkCost");
        BigDecimal scrapCost = sumField(qualityData, "scrapCost");

        List<Map<String, Object>> chartData = new ArrayList<>();

        Map<String, Object> reworkData = new LinkedHashMap<>();
        reworkData.put("category", "返工成本");
        reworkData.put("cost", reworkCost.setScale(DISPLAY_SCALE, ROUNDING_MODE));

        Map<String, Object> scrapData = new LinkedHashMap<>();
        scrapData.put("category", "报废成本");
        scrapData.put("cost", scrapCost.setScale(DISPLAY_SCALE, ROUNDING_MODE));

        chartData.add(reworkData);
        chartData.add(scrapData);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showPercentage", true);
        options.put("showLegend", true);

        return ChartConfig.builder()
                .chartType("PIE")
                .title("质量成本分布")
                .xAxisField("category")
                .yAxisField("cost")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 构建产线质量对比图表
     */
    private ChartConfig buildProductLineQualityComparisonFromData(List<Map<String, Object>> qualityData) {
        Map<String, List<Map<String, Object>>> groupedByLine = qualityData.stream()
                .collect(Collectors.groupingBy(d -> (String) d.get("productionLine")));

        List<Map<String, Object>> chartData = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByLine.entrySet()) {
            String lineName = entry.getKey();
            List<Map<String, Object>> lineData = entry.getValue();

            long totalInspections = lineData.stream()
                    .mapToLong(d -> toLong(d.get("totalInspections")))
                    .sum();
            long firstPassCount = lineData.stream()
                    .mapToLong(d -> toLong(d.get("firstPassCount")))
                    .sum();
            long defectCount = lineData.stream()
                    .mapToLong(d -> toLong(d.get("defectCount")))
                    .sum();

            BigDecimal fpy = totalInspections > 0
                    ? new BigDecimal(firstPassCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
            BigDecimal defectRate = totalInspections > 0
                    ? new BigDecimal(defectCount).divide(new BigDecimal(totalInspections), SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            Map<String, Object> dataPoint = new LinkedHashMap<>();
            dataPoint.put("productionLine", lineName);
            dataPoint.put("fpy", fpy.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("defectRate", defectRate.setScale(DISPLAY_SCALE, ROUNDING_MODE));

            chartData.add(dataPoint);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showLegend", true);
        options.put("grouped", true);

        return ChartConfig.builder()
                .chartType("BAR")
                .title("产线质量对比")
                .xAxisField("productionLine")
                .yAxisField("fpy")
                .seriesField("metric")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 生成质量洞察
     */
    private List<AIInsight> generateQualityInsights(String factoryId,
                                                     List<Map<String, Object>> qualityData,
                                                     List<MetricResult> kpiCards) {
        List<AIInsight> insights = new ArrayList<>();

        // 基于 FPY 生成洞察
        MetricResult fpyMetric = kpiCards.stream()
                .filter(m -> FPY.equals(m.getMetricCode()))
                .findFirst()
                .orElse(null);

        if (fpyMetric != null && fpyMetric.getValue() != null) {
            BigDecimal fpy = fpyMetric.getValue();
            if (fpy.compareTo(resolveFpyRedThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("RED")
                        .category("FPY")
                        .message(String.format("首次通过率仅为 %.2f%%，低于目标 95%%", fpy.doubleValue()))
                        .actionSuggestion("建议立即分析主要不良类型，进行根本原因分析 (RCA)")
                        .build());
            } else if (fpy.compareTo(resolveFpyYellowThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("YELLOW")
                        .category("FPY")
                        .message(String.format("首次通过率为 %.2f%%，有提升空间", fpy.doubleValue()))
                        .actionSuggestion("建议关注主要不良类型，制定质量改进计划")
                        .build());
            } else {
                insights.add(AIInsight.builder()
                        .level("GREEN")
                        .category("FPY")
                        .message(String.format("首次通过率达到 %.2f%%，处于良好水平", fpy.doubleValue()))
                        .actionSuggestion("继续保持当前质量管理水平")
                        .build());
            }
        }

        // 分析不良类型分布（帕累托原则）
        Map<String, Long> defectsByType = qualityData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("defectType"),
                        Collectors.summingLong(d -> toLong(d.get("defectCount")))
                ));

        String topDefectType = defectsByType.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        long totalDefects = defectsByType.values().stream().mapToLong(Long::longValue).sum();

        if (topDefectType != null && totalDefects > 0) {
            long topDefectCount = defectsByType.get(topDefectType);
            BigDecimal topDefectRatio = new BigDecimal(topDefectCount)
                    .divide(new BigDecimal(totalDefects), SCALE, ROUNDING_MODE)
                    .multiply(new BigDecimal("100"));

            if (topDefectRatio.compareTo(new BigDecimal("30")) > 0) {
                insights.add(AIInsight.builder()
                        .level("YELLOW")
                        .category("不良分析")
                        .message(String.format("「%s」占不良总数的 %.1f%%，是主要不良类型",
                                topDefectType, topDefectRatio.doubleValue()))
                        .relatedEntity(topDefectType)
                        .actionSuggestion("建议成立专项小组，针对该不良类型进行深入分析和改善")
                        .build());
            }
        }

        // 分析客户投诉
        long complaintCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("complaintCount")))
                .sum();

        if (complaintCount > 10) {
            insights.add(AIInsight.builder()
                    .level("RED")
                    .category("客户投诉")
                    .message(String.format("客户投诉数达到 %d 件，需要重点关注", complaintCount))
                    .actionSuggestion("建议分析投诉原因，制定客户投诉处理和预防措施")
                    .build());
        }

        return insights;
    }

    /**
     * 生成质量建议
     */
    private List<String> generateQualitySuggestions(String factoryId,
                                                     List<Map<String, Object>> qualityData,
                                                     List<MetricResult> kpiCards) {
        List<String> suggestions = new ArrayList<>();

        // 基于不良类型分析建议
        Map<String, Long> defectsByType = qualityData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("defectType"),
                        Collectors.summingLong(d -> toLong(d.get("defectCount")))
                ));

        // 找出前三大不良类型
        List<String> top3DefectTypes = defectsByType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (!top3DefectTypes.isEmpty()) {
            suggestions.add(String.format("主要不良类型为：%s，建议优先改善", String.join("、", top3DefectTypes)));
        }

        // 基于返工率建议
        long reworkCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("reworkCount")))
                .sum();
        long defectCount = qualityData.stream()
                .mapToLong(d -> toLong(d.get("defectCount")))
                .sum();

        if (defectCount > 0) {
            BigDecimal reworkRate = new BigDecimal(reworkCount)
                    .divide(new BigDecimal(defectCount), SCALE, ROUNDING_MODE)
                    .multiply(new BigDecimal("100"));

            if (reworkRate.compareTo(resolveReworkRateYellowThreshold(factoryId)) > 0) {
                suggestions.add("返工率较高，建议评估返工工艺，考虑是否可通过工艺改进降低返工率");
            }
        }

        // 基于产线差异建议
        List<RankingItem> lineRankings = calculateProductLineQualityRankingFromData(factoryId, qualityData);
        if (lineRankings.size() >= 2) {
            RankingItem best = lineRankings.get(0);
            RankingItem worst = lineRankings.get(lineRankings.size() - 1);

            BigDecimal gap = best.getValue().subtract(worst.getValue());
            if (gap.compareTo(new BigDecimal("3")) > 0) {
                suggestions.add(String.format("%s 的 FPY (%.2f%%) 明显高于 %s (%.2f%%)，建议推广优秀产线的管理经验",
                        best.getName(), best.getValue().doubleValue(),
                        worst.getName(), worst.getValue().doubleValue()));
            }
        }

        // 基于质量成本建议
        BigDecimal scrapCost = sumField(qualityData, "scrapCost");
        BigDecimal reworkCost = sumField(qualityData, "reworkCost");

        if (scrapCost.compareTo(reworkCost) > 0) {
            suggestions.add("报废成本高于返工成本，建议评估是否可以通过返工挽救更多不良品");
        }

        return suggestions;
    }

    /**
     * 构建空仪表盘
     */
    private DashboardResponse buildEmptyDashboard() {
        return DashboardResponse.builder()
                .kpiCards(Collections.emptyList())
                .charts(Collections.emptyMap())
                .rankings(Collections.emptyMap())
                .aiInsights(Collections.singletonList(AIInsight.builder()
                        .level("YELLOW")
                        .category("数据状态")
                        .message("当前时间范围内暂无质量数据")
                        .actionSuggestion("请录入质量检验数据或调整时间范围")
                        .build()))
                .suggestions(Collections.singletonList("请先录入质量检验数据以开始分析"))
                .generatedAt(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 工具方法 ====================

    private String determineFPYAlertLevel(String factoryId, BigDecimal fpy) {
        if (fpy.compareTo(resolveFpyRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (fpy.compareTo(resolveFpyYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    private String determineDefectRateAlertLevel(String factoryId, BigDecimal defectRate) {
        if (defectRate.compareTo(resolveDefectRateRedThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (defectRate.compareTo(resolveDefectRateYellowThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    private String determineReworkRateAlertLevel(String factoryId, BigDecimal reworkRate) {
        if (reworkRate.compareTo(resolveReworkRateRedThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (reworkRate.compareTo(resolveReworkRateYellowThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    private BigDecimal sumField(List<Map<String, Object>> data, String fieldName) {
        return data.stream()
                .map(d -> toBigDecimal(d.get(fieldName)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        return BigDecimal.ZERO;
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private String formatCurrency(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return String.format("%,.2f", value.setScale(DISPLAY_SCALE, ROUNDING_MODE).doubleValue());
    }

    private Map<String, List<Map<String, Object>>> aggregateByDay(List<Map<String, Object>> data) {
        return data.stream()
                .filter(d -> d.get("date") != null)
                .collect(Collectors.groupingBy(
                        d -> ((LocalDate) d.get("date")).toString(),
                        TreeMap::new,
                        Collectors.toList()
                ));
    }

    private Map<String, List<Map<String, Object>>> aggregateByWeek(List<Map<String, Object>> data) {
        return data.stream()
                .filter(d -> d.get("date") != null)
                .collect(Collectors.groupingBy(
                        d -> {
                            LocalDate date = (LocalDate) d.get("date");
                            LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                            return weekStart.toString();
                        },
                        TreeMap::new,
                        Collectors.toList()
                ));
    }

    private Map<String, List<Map<String, Object>>> aggregateByMonth(List<Map<String, Object>> data) {
        return data.stream()
                .filter(d -> d.get("date") != null)
                .collect(Collectors.groupingBy(
                        d -> {
                            LocalDate date = (LocalDate) d.get("date");
                            return date.getYear() + "-" + String.format("%02d", date.getMonthValue());
                        },
                        TreeMap::new,
                        Collectors.toList()
                ));
    }
}
