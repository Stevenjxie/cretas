package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.AIInsight;
import com.cretas.aims.dto.smartbi.ChartConfig;
import com.cretas.aims.dto.smartbi.DashboardResponse;
import com.cretas.aims.dto.smartbi.KPICard;
import com.cretas.aims.dto.smartbi.MetricResult;
import com.cretas.aims.dto.smartbi.RankingItem;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.smartbi.ProductionAnalysisService;
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
 * 生产分析服务实现
 *
 * 实现 SmartBI 系统中生产相关的分析，包括：
 * - OEE 概览：综合设备效率及其三个组成部分
 * - 生产效率分析：产线效率、产能利用率
 * - 设备利用率分析：设备运行状态、停机分析
 * - OEE 趋势分析：日/周/月趋势
 *
 * OEE 计算公式:
 * - OEE = Availability × Performance × Quality
 * - Availability = Actual Runtime / Planned Runtime
 * - Performance = Actual Output / Theoretical Output
 * - Quality = Good Units / Total Units
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
public class ProductionAnalysisServiceImpl implements ProductionAnalysisService {

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

    // OEE 预警阈值 fallback
    private static final BigDecimal FALLBACK_OEE_RED_THRESHOLD = new BigDecimal("65");
    private static final BigDecimal FALLBACK_OEE_YELLOW_THRESHOLD = new BigDecimal("85");

    // 可用性预警阈值 fallback
    private static final BigDecimal FALLBACK_AVAILABILITY_RED_THRESHOLD = new BigDecimal("80");
    private static final BigDecimal FALLBACK_AVAILABILITY_YELLOW_THRESHOLD = new BigDecimal("90");

    // 性能预警阈值 fallback
    private static final BigDecimal FALLBACK_PERFORMANCE_RED_THRESHOLD = new BigDecimal("75");
    private static final BigDecimal FALLBACK_PERFORMANCE_YELLOW_THRESHOLD = new BigDecimal("90");

    // 质量率预警阈值 fallback
    private static final BigDecimal FALLBACK_QUALITY_RED_THRESHOLD = new BigDecimal("95");
    private static final BigDecimal FALLBACK_QUALITY_YELLOW_THRESHOLD = new BigDecimal("98");

    // ==================== Threshold resolver shortcuts ====================

    private BigDecimal resolveOeeRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_OEE_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_OEE_RED, FALLBACK_OEE_RED_THRESHOLD);
    }

    private BigDecimal resolveOeeYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_OEE_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_OEE_YELLOW, FALLBACK_OEE_YELLOW_THRESHOLD);
    }

    private BigDecimal resolveAvailabilityRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_AVAILABILITY_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_AVAILABILITY_RED, FALLBACK_AVAILABILITY_RED_THRESHOLD);
    }

    private BigDecimal resolveAvailabilityYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_AVAILABILITY_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_AVAILABILITY_YELLOW, FALLBACK_AVAILABILITY_YELLOW_THRESHOLD);
    }

    private BigDecimal resolvePerformanceRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_PERFORMANCE_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_PERFORMANCE_RED, FALLBACK_PERFORMANCE_RED_THRESHOLD);
    }

    private BigDecimal resolvePerformanceYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_PERFORMANCE_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_PERFORMANCE_YELLOW, FALLBACK_PERFORMANCE_YELLOW_THRESHOLD);
    }

    private BigDecimal resolveQualityRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_QUALITY_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_QUALITY_RED, FALLBACK_QUALITY_RED_THRESHOLD);
    }

    private BigDecimal resolveQualityYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_QUALITY_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_QUALITY_YELLOW, FALLBACK_QUALITY_YELLOW_THRESHOLD);
    }

    // ==================== OEE 概览 ====================

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getOEEOverview(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取 OEE 概览: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // 获取生产数据（使用模拟数据）
        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);

        if (productionData.isEmpty()) {
            log.warn("未找到生产数据: factoryId={}", factoryId);
            return buildEmptyDashboard();
        }

        // 计算 KPI 卡片
        List<MetricResult> metricResults = calculateOEEKpiCards(productionData, factoryId, startDate, endDate);
        List<KPICard> kpiCards = convertToKPICards(metricResults);

        // 生成图表
        Map<String, ChartConfig> charts = new LinkedHashMap<>();
        charts.put("oee_trend", buildOEETrendChartFromData(productionData, "DAY"));
        charts.put("production_line_comparison", buildProductionLineComparisonFromData(productionData));
        charts.put("downtime_distribution", buildDowntimeDistributionFromData(productionData));

        // 生成排名
        Map<String, List<RankingItem>> rankings = new LinkedHashMap<>();
        rankings.put("equipment", calculateEquipmentRankingFromData(factoryId, productionData));
        rankings.put("production_line", calculateProductionLineRankingFromData(factoryId, productionData));

        // 生成 AI 洞察
        List<AIInsight> aiInsights = generateOEEInsights(factoryId, productionData, metricResults);

        // 生成建议
        List<String> suggestions = generateOEESuggestions(factoryId, productionData, metricResults);

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

    // ==================== OEE 指标详情 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getOEEMetrics(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取 OEE 详细指标: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);

        if (productionData.isEmpty()) {
            log.warn("未找到生产数据: factoryId={}", factoryId);
            return Collections.emptyList();
        }

        return calculateOEEDetailedMetrics(factoryId, productionData);
    }

    // ==================== 生产效率分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getProductionEfficiency(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取生产效率指标: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);

        if (productionData.isEmpty()) {
            return Collections.emptyList();
        }

        List<MetricResult> metrics = new ArrayList<>();

        // 计算整体产能利用率
        BigDecimal totalActualOutput = sumField(productionData, "actualOutput");
        BigDecimal totalTheoreticalOutput = sumField(productionData, "theoreticalOutput");
        BigDecimal capacityUtilization = totalTheoreticalOutput.compareTo(BigDecimal.ZERO) > 0
                ? totalActualOutput.divide(totalTheoreticalOutput, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode(CAPACITY_UTILIZATION)
                .metricName("产能利用率")
                .value(capacityUtilization.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", capacityUtilization.doubleValue()))
                .unit("%")
                .alertLevel(determinePerformanceAlertLevel(factoryId, capacityUtilization))
                .build());

        // 计算平均节拍时间
        long totalGoodUnits = productionData.stream()
                .mapToLong(d -> toLong(d.get("goodUnits")))
                .sum();
        BigDecimal totalRuntime = sumField(productionData, "actualRuntime");
        BigDecimal avgCycleTime = totalGoodUnits > 0
                ? totalRuntime.divide(new BigDecimal(totalGoodUnits), SCALE, ROUNDING_MODE)
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode("CYCLE_TIME")
                .metricName("平均节拍时间")
                .value(avgCycleTime.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.2f 分钟/件", avgCycleTime.doubleValue()))
                .unit("分钟/件")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 计算计划达成率
        BigDecimal totalPlannedOutput = sumField(productionData, "plannedOutput");
        BigDecimal achievementRate = totalPlannedOutput.compareTo(BigDecimal.ZERO) > 0
                ? totalActualOutput.divide(totalPlannedOutput, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode("ACHIEVEMENT_RATE")
                .metricName("计划达成率")
                .value(achievementRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", achievementRate.doubleValue()))
                .unit("%")
                .alertLevel(determineOEEAlertLevel(factoryId, achievementRate))
                .build());

        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getProductionLineRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取产线效率排名: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);
        return calculateProductionLineRankingFromData(factoryId, productionData);
    }

    // ==================== 设备利用率分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getEquipmentUtilization(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取设备利用率指标: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);

        if (productionData.isEmpty()) {
            return Collections.emptyList();
        }

        List<MetricResult> metrics = new ArrayList<>();

        // 设备利用率
        BigDecimal totalPlannedRuntime = sumField(productionData, "plannedRuntime");
        BigDecimal totalActualRuntime = sumField(productionData, "actualRuntime");
        BigDecimal utilization = totalPlannedRuntime.compareTo(BigDecimal.ZERO) > 0
                ? totalActualRuntime.divide(totalPlannedRuntime, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode(EQUIPMENT_UTILIZATION)
                .metricName("设备利用率")
                .value(utilization.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", utilization.doubleValue()))
                .unit("%")
                .alertLevel(determineAvailabilityAlertLevel(factoryId, utilization))
                .build());

        // 停机时间
        BigDecimal totalDowntime = sumField(productionData, "downtime");
        metrics.add(MetricResult.builder()
                .metricCode(DOWNTIME)
                .metricName("总停机时间")
                .value(totalDowntime.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f 小时", totalDowntime.doubleValue()))
                .unit("小时")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 故障次数
        long failureCount = productionData.stream()
                .mapToLong(d -> toLong(d.get("failureCount")))
                .sum();
        metrics.add(MetricResult.builder()
                .metricCode(FAILURE_COUNT)
                .metricName("故障次数")
                .value(new BigDecimal(failureCount))
                .formattedValue(String.format("%d 次", failureCount))
                .unit("次")
                .alertLevel(failureCount > 10 ? MetricResult.AlertLevel.RED.name() :
                        (failureCount > 5 ? MetricResult.AlertLevel.YELLOW.name() : MetricResult.AlertLevel.GREEN.name()))
                .build());

        // MTBF (平均故障间隔时间)
        BigDecimal mtbf = failureCount > 0
                ? totalActualRuntime.divide(new BigDecimal(failureCount), SCALE, ROUNDING_MODE)
                : totalActualRuntime;
        metrics.add(MetricResult.builder()
                .metricCode(MTBF)
                .metricName("平均故障间隔 (MTBF)")
                .value(mtbf.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f 小时", mtbf.doubleValue()))
                .unit("小时")
                .alertLevel(mtbf.compareTo(new BigDecimal("100")) < 0 ? MetricResult.AlertLevel.YELLOW.name() : MetricResult.AlertLevel.GREEN.name())
                .build());

        // MTTR (平均修复时间)
        BigDecimal mttr = failureCount > 0
                ? totalDowntime.divide(new BigDecimal(failureCount), SCALE, ROUNDING_MODE)
                : BigDecimal.ZERO;
        metrics.add(MetricResult.builder()
                .metricCode(MTTR)
                .metricName("平均修复时间 (MTTR)")
                .value(mttr.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f 小时", mttr.doubleValue()))
                .unit("小时")
                .alertLevel(mttr.compareTo(new BigDecimal("4")) > 0 ? MetricResult.AlertLevel.RED.name() :
                        (mttr.compareTo(new BigDecimal("2")) > 0 ? MetricResult.AlertLevel.YELLOW.name() : MetricResult.AlertLevel.GREEN.name()))
                .build());

        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getEquipmentRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取设备排名: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);
        return calculateEquipmentRankingFromData(factoryId, productionData);
    }

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getDowntimeDistributionChart(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取停机原因分布图表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);
        return buildDowntimeDistributionFromData(productionData);
    }

    // ==================== OEE 趋势分析 ====================

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getOEETrendChart(String factoryId, LocalDate startDate, LocalDate endDate, String period) {
        log.info("获取 OEE 趋势图表: factoryId={}, startDate={}, endDate={}, period={}",
                factoryId, startDate, endDate, period);

        List<Map<String, Object>> productionData = generateMockProductionData(factoryId, startDate, endDate);
        return buildOEETrendChartFromData(productionData, period);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 生成模拟生产数据
     * 实际实现时应从 ProductionBatch 和 BatchEquipmentUsage 实体查询
     */
    private List<Map<String, Object>> generateMockProductionData(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> data = new ArrayList<>();
        Random random = new Random(factoryId.hashCode());

        String[] productionLines = {"产线A", "产线B", "产线C", "产线D"};
        String[] equipments = {"设备1", "设备2", "设备3", "设备4", "设备5"};
        String[] downtimeReasons = {"计划维护", "设备故障", "物料短缺", "换型调整", "质量问题"};

        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);

        for (int i = 0; i <= daysBetween; i++) {
            LocalDate date = startDate.plusDays(i);

            for (String line : productionLines) {
                for (String equipment : equipments) {
                    Map<String, Object> record = new LinkedHashMap<>();

                    record.put("factoryId", factoryId);
                    record.put("date", date);
                    record.put("productionLine", line);
                    record.put("equipment", equipment);

                    // 计划运行时间 (小时)
                    BigDecimal plannedRuntime = new BigDecimal(8 + random.nextInt(8));
                    record.put("plannedRuntime", plannedRuntime);

                    // 停机时间 (小时)
                    BigDecimal downtime = new BigDecimal(random.nextDouble() * 2).setScale(SCALE, ROUNDING_MODE);
                    record.put("downtime", downtime);

                    // 实际运行时间
                    BigDecimal actualRuntime = plannedRuntime.subtract(downtime);
                    record.put("actualRuntime", actualRuntime);

                    // 理论产量
                    int theoreticalOutput = 100 + random.nextInt(100);
                    record.put("theoreticalOutput", new BigDecimal(theoreticalOutput));

                    // 实际产量
                    int actualOutput = (int) (theoreticalOutput * (0.8 + random.nextDouble() * 0.2));
                    record.put("actualOutput", new BigDecimal(actualOutput));
                    record.put("plannedOutput", new BigDecimal((int) (theoreticalOutput * 0.95)));

                    // 良品数
                    int goodUnits = (int) (actualOutput * (0.95 + random.nextDouble() * 0.05));
                    record.put("goodUnits", goodUnits);
                    record.put("totalUnits", actualOutput);

                    // 故障次数
                    int failureCount = random.nextInt(3);
                    record.put("failureCount", failureCount);

                    // 停机原因
                    record.put("downtimeReason", downtimeReasons[random.nextInt(downtimeReasons.length)]);

                    data.add(record);
                }
            }
        }

        return data;
    }

    /**
     * 计算 OEE KPI 卡片
     */
    private List<MetricResult> calculateOEEKpiCards(List<Map<String, Object>> productionData,
                                                     String factoryId,
                                                     LocalDate startDate,
                                                     LocalDate endDate) {
        List<MetricResult> kpiCards = new ArrayList<>();

        // 计算可用性
        BigDecimal totalPlannedRuntime = sumField(productionData, "plannedRuntime");
        BigDecimal totalActualRuntime = sumField(productionData, "actualRuntime");
        BigDecimal availability = totalPlannedRuntime.compareTo(BigDecimal.ZERO) > 0
                ? totalActualRuntime.divide(totalPlannedRuntime, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // 计算性能
        BigDecimal totalActualOutput = sumField(productionData, "actualOutput");
        BigDecimal totalTheoreticalOutput = sumField(productionData, "theoreticalOutput");
        BigDecimal performance = totalTheoreticalOutput.compareTo(BigDecimal.ZERO) > 0
                ? totalActualOutput.divide(totalTheoreticalOutput, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // 计算质量率
        long totalGoodUnits = productionData.stream()
                .mapToLong(d -> toLong(d.get("goodUnits")))
                .sum();
        long totalUnits = productionData.stream()
                .mapToLong(d -> toLong(d.get("totalUnits")))
                .sum();
        BigDecimal quality = totalUnits > 0
                ? new BigDecimal(totalGoodUnits).divide(new BigDecimal(totalUnits), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // 计算 OEE
        BigDecimal oee = availability.multiply(performance).multiply(quality)
                .divide(new BigDecimal("10000"), SCALE, ROUNDING_MODE);

        // OEE 卡片
        kpiCards.add(MetricResult.builder()
                .metricCode(OEE)
                .metricName("综合设备效率 (OEE)")
                .value(oee.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", oee.doubleValue()))
                .unit("%")
                .alertLevel(determineOEEAlertLevel(factoryId, oee))
                .description("OEE = 可用性 x 性能 x 质量")
                .build());

        // 可用性卡片
        kpiCards.add(MetricResult.builder()
                .metricCode(AVAILABILITY)
                .metricName("可用性")
                .value(availability.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", availability.doubleValue()))
                .unit("%")
                .alertLevel(determineAvailabilityAlertLevel(factoryId, availability))
                .description("实际运行时间 / 计划运行时间")
                .build());

        // 性能卡片
        kpiCards.add(MetricResult.builder()
                .metricCode(PERFORMANCE)
                .metricName("性能")
                .value(performance.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", performance.doubleValue()))
                .unit("%")
                .alertLevel(determinePerformanceAlertLevel(factoryId, performance))
                .description("实际产量 / 理论产量")
                .build());

        // 质量率卡片
        kpiCards.add(MetricResult.builder()
                .metricCode(QUALITY_RATE)
                .metricName("质量率")
                .value(quality.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", quality.doubleValue()))
                .unit("%")
                .alertLevel(determineQualityAlertLevel(factoryId, quality))
                .description("良品数 / 总产量")
                .build());

        return kpiCards;
    }

    /**
     * 计算 OEE 详细指标
     */
    private List<MetricResult> calculateOEEDetailedMetrics(String factoryId, List<Map<String, Object>> productionData) {
        List<MetricResult> metrics = new ArrayList<>();

        // 计划运行时间
        BigDecimal totalPlannedRuntime = sumField(productionData, "plannedRuntime");
        metrics.add(MetricResult.of(PLANNED_RUNTIME, "计划运行时间", totalPlannedRuntime, "小时"));

        // 实际运行时间
        BigDecimal totalActualRuntime = sumField(productionData, "actualRuntime");
        metrics.add(MetricResult.of(ACTUAL_RUNTIME, "实际运行时间", totalActualRuntime, "小时"));

        // 停机时间
        BigDecimal totalDowntime = sumField(productionData, "downtime");
        metrics.add(MetricResult.of(DOWNTIME, "停机时间", totalDowntime, "小时"));

        // 可用性
        BigDecimal availability = totalPlannedRuntime.compareTo(BigDecimal.ZERO) > 0
                ? totalActualRuntime.divide(totalPlannedRuntime, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.of(AVAILABILITY, "可用性", availability, "%",
                availability.compareTo(resolveAvailabilityRedThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.RED :
                        (availability.compareTo(resolveAvailabilityYellowThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.YELLOW : MetricResult.AlertLevel.GREEN)));

        // 理论产量
        BigDecimal totalTheoreticalOutput = sumField(productionData, "theoreticalOutput");
        metrics.add(MetricResult.of(THEORETICAL_OUTPUT, "理论产量", totalTheoreticalOutput, "件"));

        // 实际产量
        BigDecimal totalActualOutput = sumField(productionData, "actualOutput");
        metrics.add(MetricResult.of(ACTUAL_OUTPUT, "实际产量", totalActualOutput, "件"));

        // 性能
        BigDecimal performance = totalTheoreticalOutput.compareTo(BigDecimal.ZERO) > 0
                ? totalActualOutput.divide(totalTheoreticalOutput, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.of(PERFORMANCE, "性能", performance, "%",
                performance.compareTo(resolvePerformanceRedThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.RED :
                        (performance.compareTo(resolvePerformanceYellowThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.YELLOW : MetricResult.AlertLevel.GREEN)));

        // 良品数
        long totalGoodUnits = productionData.stream()
                .mapToLong(d -> toLong(d.get("goodUnits")))
                .sum();
        metrics.add(MetricResult.of(GOOD_UNITS, "良品数", new BigDecimal(totalGoodUnits), "件"));

        // 质量率
        long totalUnits = productionData.stream()
                .mapToLong(d -> toLong(d.get("totalUnits")))
                .sum();
        BigDecimal quality = totalUnits > 0
                ? new BigDecimal(totalGoodUnits).divide(new BigDecimal(totalUnits), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.of(QUALITY_RATE, "质量率", quality, "%",
                quality.compareTo(resolveQualityRedThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.RED :
                        (quality.compareTo(resolveQualityYellowThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.YELLOW : MetricResult.AlertLevel.GREEN)));

        // OEE
        BigDecimal oee = availability.multiply(performance).multiply(quality)
                .divide(new BigDecimal("10000"), SCALE, ROUNDING_MODE);
        metrics.add(MetricResult.of(OEE, "综合设备效率 (OEE)", oee, "%",
                oee.compareTo(resolveOeeRedThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.RED :
                        (oee.compareTo(resolveOeeYellowThreshold(factoryId)) < 0 ? MetricResult.AlertLevel.YELLOW : MetricResult.AlertLevel.GREEN)));

        return metrics;
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
     * 计算设备排名
     */
    private List<RankingItem> calculateEquipmentRankingFromData(String factoryId, List<Map<String, Object>> productionData) {
        Map<String, List<Map<String, Object>>> groupedByEquipment = productionData.stream()
                .collect(Collectors.groupingBy(d -> (String) d.get("equipment")));

        List<RankingItem> rankings = new ArrayList<>();
        BigDecimal oeeYellow = resolveOeeYellowThreshold(factoryId);

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByEquipment.entrySet()) {
            String equipmentName = entry.getKey();
            List<Map<String, Object>> equipmentData = entry.getValue();

            // 计算该设备的 OEE
            BigDecimal availability = calculateAvailability(equipmentData);
            BigDecimal performance = calculatePerformance(equipmentData);
            BigDecimal quality = calculateQuality(equipmentData);
            BigDecimal oee = availability.multiply(performance).multiply(quality)
                    .divide(new BigDecimal("10000"), SCALE, ROUNDING_MODE);

            rankings.add(RankingItem.builder()
                    .name(equipmentName)
                    .value(oee.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .target(oeeYellow)
                    .completionRate(oee.divide(oeeYellow, SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100")).setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(determineOEEAlertLevel(factoryId, oee))
                    .build());
        }

        // 排序并设置排名
        rankings.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (int i = 0; i < rankings.size(); i++) {
            rankings.get(i).setRank(i + 1);
        }

        return rankings;
    }

    /**
     * 计算产线排名
     */
    private List<RankingItem> calculateProductionLineRankingFromData(String factoryId, List<Map<String, Object>> productionData) {
        Map<String, List<Map<String, Object>>> groupedByLine = productionData.stream()
                .collect(Collectors.groupingBy(d -> (String) d.get("productionLine")));

        List<RankingItem> rankings = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByLine.entrySet()) {
            String lineName = entry.getKey();
            List<Map<String, Object>> lineData = entry.getValue();

            // 计算该产线的效率
            BigDecimal actualOutput = sumField(lineData, "actualOutput");
            BigDecimal plannedOutput = sumField(lineData, "plannedOutput");
            BigDecimal efficiency = plannedOutput.compareTo(BigDecimal.ZERO) > 0
                    ? actualOutput.divide(plannedOutput, SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            rankings.add(RankingItem.builder()
                    .name(lineName)
                    .value(efficiency.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .target(new BigDecimal("100"))
                    .completionRate(efficiency.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(determineOEEAlertLevel(factoryId, efficiency))
                    .build());
        }

        // 排序并设置排名
        rankings.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (int i = 0; i < rankings.size(); i++) {
            rankings.get(i).setRank(i + 1);
        }

        return rankings;
    }

    /**
     * 构建 OEE 趋势图表
     */
    private ChartConfig buildOEETrendChartFromData(List<Map<String, Object>> productionData, String period) {
        Map<String, List<Map<String, Object>>> groupedByDate;

        switch (period.toUpperCase()) {
            case "WEEK":
                groupedByDate = aggregateByWeek(productionData);
                break;
            case "MONTH":
                groupedByDate = aggregateByMonth(productionData);
                break;
            case "DAY":
            default:
                groupedByDate = aggregateByDay(productionData);
                break;
        }

        List<Map<String, Object>> chartData = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByDate.entrySet()
                .stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toList())) {

            String dateKey = entry.getKey();
            List<Map<String, Object>> dayData = entry.getValue();

            BigDecimal availability = calculateAvailability(dayData);
            BigDecimal performance = calculatePerformance(dayData);
            BigDecimal quality = calculateQuality(dayData);
            BigDecimal oee = availability.multiply(performance).multiply(quality)
                    .divide(new BigDecimal("10000"), SCALE, ROUNDING_MODE);

            Map<String, Object> dataPoint = new LinkedHashMap<>();
            dataPoint.put("date", dateKey);
            dataPoint.put("oee", oee.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("availability", availability.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("performance", performance.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("quality", quality.setScale(DISPLAY_SCALE, ROUNDING_MODE));

            chartData.add(dataPoint);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showLegend", true);
        options.put("multiLine", true);
        options.put("yAxisMax", 100);

        return ChartConfig.builder()
                .chartType("LINE")
                .title("OEE 趋势")
                .xAxisField("date")
                .yAxisField("oee")
                .seriesField("metric")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 构建产线对比图表
     */
    private ChartConfig buildProductionLineComparisonFromData(List<Map<String, Object>> productionData) {
        Map<String, List<Map<String, Object>>> groupedByLine = productionData.stream()
                .collect(Collectors.groupingBy(d -> (String) d.get("productionLine")));

        List<Map<String, Object>> chartData = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByLine.entrySet()) {
            String lineName = entry.getKey();
            List<Map<String, Object>> lineData = entry.getValue();

            BigDecimal availability = calculateAvailability(lineData);
            BigDecimal performance = calculatePerformance(lineData);
            BigDecimal quality = calculateQuality(lineData);
            BigDecimal oee = availability.multiply(performance).multiply(quality)
                    .divide(new BigDecimal("10000"), SCALE, ROUNDING_MODE);

            Map<String, Object> dataPoint = new LinkedHashMap<>();
            dataPoint.put("productionLine", lineName);
            dataPoint.put("oee", oee.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("availability", availability.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("performance", performance.setScale(DISPLAY_SCALE, ROUNDING_MODE));
            dataPoint.put("quality", quality.setScale(DISPLAY_SCALE, ROUNDING_MODE));

            chartData.add(dataPoint);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showLegend", true);
        options.put("grouped", true);

        return ChartConfig.builder()
                .chartType("BAR")
                .title("产线 OEE 对比")
                .xAxisField("productionLine")
                .yAxisField("oee")
                .seriesField("metric")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 构建停机原因分布图表
     */
    private ChartConfig buildDowntimeDistributionFromData(List<Map<String, Object>> productionData) {
        Map<String, BigDecimal> downtimeByReason = productionData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("downtimeReason"),
                        Collectors.reducing(BigDecimal.ZERO,
                                d -> toBigDecimal(d.get("downtime")),
                                BigDecimal::add)
                ));

        List<Map<String, Object>> chartData = downtimeByReason.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> dataPoint = new LinkedHashMap<>();
                    dataPoint.put("reason", entry.getKey());
                    dataPoint.put("downtime", entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE));
                    return dataPoint;
                })
                .collect(Collectors.toList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showPercentage", true);
        options.put("showLegend", true);

        return ChartConfig.builder()
                .chartType("PIE")
                .title("停机原因分布")
                .xAxisField("reason")
                .yAxisField("downtime")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 生成 OEE 洞察
     */
    private List<AIInsight> generateOEEInsights(String factoryId,
                                                 List<Map<String, Object>> productionData,
                                                 List<MetricResult> kpiCards) {
        List<AIInsight> insights = new ArrayList<>();

        // 基于 OEE 生成洞察
        MetricResult oeeMetric = kpiCards.stream()
                .filter(m -> OEE.equals(m.getMetricCode()))
                .findFirst()
                .orElse(null);

        if (oeeMetric != null && oeeMetric.getValue() != null) {
            BigDecimal oee = oeeMetric.getValue();
            if (oee.compareTo(resolveOeeRedThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("RED")
                        .category("OEE")
                        .message(String.format("综合设备效率仅为 %.1f%%，低于行业标准 65%%", oee.doubleValue()))
                        .actionSuggestion("建议立即分析可用性、性能、质量三个维度，找出主要损失原因")
                        .build());
            } else if (oee.compareTo(resolveOeeYellowThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("YELLOW")
                        .category("OEE")
                        .message(String.format("综合设备效率为 %.1f%%，有提升空间", oee.doubleValue()))
                        .actionSuggestion("建议关注效率最低的设备和产线，制定改进计划")
                        .build());
            } else {
                insights.add(AIInsight.builder()
                        .level("GREEN")
                        .category("OEE")
                        .message(String.format("综合设备效率达到 %.1f%%，处于良好水平", oee.doubleValue()))
                        .actionSuggestion("继续保持当前管理水平，可考虑向世界级制造 (>85%) 目标迈进")
                        .build());
            }
        }

        // 分析可用性
        MetricResult availabilityMetric = kpiCards.stream()
                .filter(m -> AVAILABILITY.equals(m.getMetricCode()))
                .findFirst()
                .orElse(null);

        if (availabilityMetric != null && availabilityMetric.getValue() != null) {
            BigDecimal availability = availabilityMetric.getValue();
            if (availability.compareTo(resolveAvailabilityRedThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("RED")
                        .category("可用性")
                        .message(String.format("设备可用性仅为 %.1f%%，停机时间过长", availability.doubleValue()))
                        .actionSuggestion("建议分析停机原因，优化计划维护安排，减少非计划停机")
                        .build());
            }
        }

        // 分析质量
        MetricResult qualityMetric = kpiCards.stream()
                .filter(m -> QUALITY_RATE.equals(m.getMetricCode()))
                .findFirst()
                .orElse(null);

        if (qualityMetric != null && qualityMetric.getValue() != null) {
            BigDecimal quality = qualityMetric.getValue();
            if (quality.compareTo(resolveQualityRedThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("RED")
                        .category("质量")
                        .message(String.format("质量率仅为 %.1f%%，不良品率过高", quality.doubleValue()))
                        .actionSuggestion("建议加强首件检验和过程控制，分析不良原因并采取纠正措施")
                        .build());
            }
        }

        return insights;
    }

    /**
     * 生成 OEE 建议
     */
    private List<String> generateOEESuggestions(String factoryId,
                                                 List<Map<String, Object>> productionData,
                                                 List<MetricResult> kpiCards) {
        List<String> suggestions = new ArrayList<>();

        // 分析停机原因
        Map<String, BigDecimal> downtimeByReason = productionData.stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.get("downtimeReason"),
                        Collectors.reducing(BigDecimal.ZERO,
                                d -> toBigDecimal(d.get("downtime")),
                                BigDecimal::add)
                ));

        String topDowntimeReason = downtimeByReason.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (topDowntimeReason != null) {
            suggestions.add(String.format("主要停机原因是「%s」，建议重点改善", topDowntimeReason));
        }

        // 基于故障次数建议
        long totalFailures = productionData.stream()
                .mapToLong(d -> toLong(d.get("failureCount")))
                .sum();

        if (totalFailures > 10) {
            suggestions.add("故障次数较多，建议加强预防性维护，制定设备保养计划");
        }

        // 基于各指标建议
        BigDecimal availabilityYellow = resolveAvailabilityYellowThreshold(factoryId);
        BigDecimal performanceYellow = resolvePerformanceYellowThreshold(factoryId);
        for (MetricResult metric : kpiCards) {
            if (AVAILABILITY.equals(metric.getMetricCode()) &&
                    metric.getValue().compareTo(availabilityYellow) < 0) {
                suggestions.add("可用性偏低，建议优化换型时间、减少计划外停机");
            }
            if (PERFORMANCE.equals(metric.getMetricCode()) &&
                    metric.getValue().compareTo(performanceYellow) < 0) {
                suggestions.add("性能指标偏低，建议检查设备速度设定、消除微停机");
            }
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
                        .message("当前时间范围内暂无生产数据")
                        .actionSuggestion("请录入生产数据或调整时间范围")
                        .build()))
                .suggestions(Collections.singletonList("请先录入生产数据以开始分析"))
                .generatedAt(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 工具方法 ====================

    private BigDecimal calculateAvailability(List<Map<String, Object>> data) {
        BigDecimal plannedRuntime = sumField(data, "plannedRuntime");
        BigDecimal actualRuntime = sumField(data, "actualRuntime");
        return plannedRuntime.compareTo(BigDecimal.ZERO) > 0
                ? actualRuntime.divide(plannedRuntime, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
    }

    private BigDecimal calculatePerformance(List<Map<String, Object>> data) {
        BigDecimal actualOutput = sumField(data, "actualOutput");
        BigDecimal theoreticalOutput = sumField(data, "theoreticalOutput");
        return theoreticalOutput.compareTo(BigDecimal.ZERO) > 0
                ? actualOutput.divide(theoreticalOutput, SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
    }

    private BigDecimal calculateQuality(List<Map<String, Object>> data) {
        long goodUnits = data.stream().mapToLong(d -> toLong(d.get("goodUnits"))).sum();
        long totalUnits = data.stream().mapToLong(d -> toLong(d.get("totalUnits"))).sum();
        return totalUnits > 0
                ? new BigDecimal(goodUnits).divide(new BigDecimal(totalUnits), SCALE, ROUNDING_MODE)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
    }

    private String determineOEEAlertLevel(String factoryId, BigDecimal oee) {
        if (oee.compareTo(resolveOeeRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (oee.compareTo(resolveOeeYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    private String determineAvailabilityAlertLevel(String factoryId, BigDecimal availability) {
        if (availability.compareTo(resolveAvailabilityRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (availability.compareTo(resolveAvailabilityYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    private String determinePerformanceAlertLevel(String factoryId, BigDecimal performance) {
        if (performance.compareTo(resolvePerformanceRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (performance.compareTo(resolvePerformanceYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    private String determineQualityAlertLevel(String factoryId, BigDecimal quality) {
        if (quality.compareTo(resolveQualityRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (quality.compareTo(resolveQualityYellowThreshold(factoryId)) < 0) {
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
