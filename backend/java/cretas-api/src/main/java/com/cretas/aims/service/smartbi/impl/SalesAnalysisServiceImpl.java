package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.AIInsight;
import com.cretas.aims.dto.smartbi.ChartConfig;
import com.cretas.aims.dto.smartbi.DashboardResponse;
import com.cretas.aims.dto.smartbi.KPICard;
import com.cretas.aims.dto.smartbi.MetricResult;
import com.cretas.aims.dto.smartbi.RankingItem;
import com.cretas.aims.entity.smartbi.SmartBiSalesData;
import com.cretas.aims.repository.smartbi.SmartBiSalesDataRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.smartbi.GoldDashboardBuilder;
import com.cretas.aims.service.smartbi.MetricCalculatorService;
import com.cretas.aims.service.smartbi.SalesAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 销售分析服务实现
 *
 * 实现 SmartBI 系统中销售相关的分析，包括：
 * - 销售概览：KPI 卡片、图表、排名、AI 洞察
 * - 销售员分析：排名、完成率、人均产出
 * - 产品分析：销量排名、占比分布
 * - 客户分析：Top 10 客户贡献
 * - 趋势分析：日/周/月销售趋势
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
public class SalesAnalysisServiceImpl implements SalesAnalysisService {

    private final SmartBiSalesDataRepository salesDataRepository;
    private final MetricCalculatorService metricCalculatorService;
    private final GoldDashboardBuilder goldDashboardBuilder;

    /**
     * Shared v1 Phase B flag with FinanceAnalysisServiceImpl. When true,
     * getSalesOverview tries Gold (same finance_summary endpoint) FIRST
     * and serves a Gold-backed DashboardResponse; on any failure falls
     * back to the legacy smart_bi_sales_data path transparently.
     */
    @Value("${smartbi.gold.read-primary.enabled:false}")
    private boolean goldReadPrimaryEnabled;

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

    private static final BigDecimal FALLBACK_TARGET_RED_THRESHOLD = new BigDecimal("60");
    private static final BigDecimal FALLBACK_TARGET_YELLOW_THRESHOLD = new BigDecimal("85");
    private static final BigDecimal FALLBACK_MARGIN_RED_THRESHOLD = new BigDecimal("15");
    private static final BigDecimal FALLBACK_MARGIN_YELLOW_THRESHOLD = new BigDecimal("25");
    private static final BigDecimal FALLBACK_GROWTH_RED_THRESHOLD = new BigDecimal("-20");
    private static final BigDecimal FALLBACK_GROWTH_YELLOW_THRESHOLD = new BigDecimal("-5");

    // ==================== Threshold resolver shortcuts ====================

    private BigDecimal resolveTargetRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_TARGET_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.SALES_TARGET_RED, FALLBACK_TARGET_RED_THRESHOLD);
    }

    private BigDecimal resolveTargetYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_TARGET_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.SALES_TARGET_YELLOW, FALLBACK_TARGET_YELLOW_THRESHOLD);
    }

    private BigDecimal resolveMarginRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_MARGIN_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.SALES_MARGIN_RED, FALLBACK_MARGIN_RED_THRESHOLD);
    }

    private BigDecimal resolveMarginYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_MARGIN_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.SALES_MARGIN_YELLOW, FALLBACK_MARGIN_YELLOW_THRESHOLD);
    }

    private BigDecimal resolveGrowthRedThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_GROWTH_RED_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.SALES_GROWTH_RED, FALLBACK_GROWTH_RED_THRESHOLD);
    }

    private BigDecimal resolveGrowthYellowThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_GROWTH_YELLOW_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.SALES_GROWTH_YELLOW, FALLBACK_GROWTH_YELLOW_THRESHOLD);
    }

    // ==================== 销售概览 ====================

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getSalesOverview(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取销售概览(聚合优化): factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // v1 Phase B: try Gold first when enabled. 分析概览 (Dashboard.vue) also
        // hits this method, so flipping SMARTBI_GOLD_READ_PRIMARY_ENABLED=true
        // makes both pages show Silver+Gold-backed KPIs for tenants with POS
        // data in Silver. Legacy path still serves when Gold is empty or fails.
        if (goldReadPrimaryEnabled && goldDashboardBuilder != null) {
            try {
                // Apr 25 Phase B4: Dashboard 经营驾驶舱 charts (sales_trend +
                // category_distribution) were empty for restaurant tenants
                // because the original cutover only included KPIs.
                // buildFromGoldWithCharts adds the chart fetches; KPI null-
                // contract is preserved (returns null when Gold is empty).
                DashboardResponse goldResponse = goldDashboardBuilder
                        .buildFromGoldWithCharts(factoryId, startDate, endDate);
                if (goldResponse != null) {
                    log.info("[gold-primary] sales factory={} range={}..{} served from Gold",
                            factoryId, startDate, endDate);
                    return goldResponse;
                }
                // Gold returned null = revenue=0 AND bills=0 in Silver. Gold is authoritative
                // under primary flag, so skip the slow legacy scan (~50s on empty ranges per
                // Bug #417) and return empty directly. Fallback to legacy only triggers on
                // actual Gold failures (exception branch below).
                log.info("[gold-primary] sales factory={} range={}..{} Gold empty — skipping legacy",
                        factoryId, startDate, endDate);
                return buildEmptyDashboard();
            } catch (Exception e) {
                log.warn("[gold-primary] sales factory={} failed, falling back to legacy: {}",
                        factoryId, e.getMessage());
            }
        }

        // Step 1: Use DB aggregation for KPI cards (single query instead of loading all rows)
        Object[] kpiSummary = salesDataRepository.findKpiSummary(factoryId, startDate, endDate);
        // JPA may wrap single-row aggregate as nested Object[] — unwrap if needed
        if (kpiSummary != null && kpiSummary.length == 1 && kpiSummary[0] instanceof Object[]) {
            kpiSummary = (Object[]) kpiSummary[0];
        }
        if (kpiSummary == null || kpiSummary.length < 6) {
            log.warn("未找到销售数据(查询结果为空): factoryId={}", factoryId);
            return buildEmptyDashboard();
        }
        BigDecimal totalSales = toBigDecimal(kpiSummary[0]);
        BigDecimal totalQuantity = toBigDecimal(kpiSummary[1]);
        BigDecimal totalProfit = toBigDecimal(kpiSummary[2]);
        BigDecimal totalCost = toBigDecimal(kpiSummary[3]);
        BigDecimal totalTarget = toBigDecimal(kpiSummary[4]);
        long orderCount = kpiSummary[5] instanceof Number ? ((Number) kpiSummary[5]).longValue() : 0L;

        if (totalSales.compareTo(BigDecimal.ZERO) == 0 && orderCount == 0) {
            log.warn("未找到销售数据: factoryId={}", factoryId);
            return buildEmptyDashboard();
        }

        // Step 2: Build KPI cards from aggregated data
        List<MetricResult> metricResults = buildKpiFromAggregates(
                totalSales, totalQuantity, totalProfit, totalCost, totalTarget, orderCount,
                factoryId, startDate, endDate);
        List<KPICard> kpiCards = convertToKPICards(metricResults);

        // Step 3: Build charts from lightweight aggregation queries (no full load)
        Map<String, ChartConfig> charts = new LinkedHashMap<>();

        // Trend chart from daily aggregation
        List<Object[]> dailyTrend = salesDataRepository.findDailySalesTrend(factoryId, startDate, endDate);
        if (!dailyTrend.isEmpty()) {
            charts.put("销售趋势", buildTrendChartFromAggregates(dailyTrend));
        }

        // Product pie chart from category aggregation
        List<Object[]> categoryData = salesDataRepository.findSalesByProductCategory(factoryId, startDate, endDate);
        if (!categoryData.isEmpty()) {
            charts.put("产品分布", buildPieChartFromAggregates(categoryData));
        }

        // Step 4: Rankings from existing aggregation queries
        List<Object[]> salespersonData = salesDataRepository.findSalesBySalesperson(factoryId, startDate, endDate);
        List<RankingItem> salespersonRankings = buildRankingsFromAggregates(salespersonData);
        Map<String, List<RankingItem>> rankings = new LinkedHashMap<>();
        rankings.put("salesperson", salespersonRankings);

        // Step 5: AI insights from aggregated metrics (no full data needed)
        List<AIInsight> aiInsights = generateAiInsightsFromMetrics(metricResults, totalSales, totalProfit, orderCount);
        List<String> suggestions = generateSuggestionsFromMetrics(metricResults, totalSales, totalTarget);

        return DashboardResponse.builder()
                .kpiCards(kpiCards)
                .charts(charts)
                .rankings(rankings)
                .aiInsights(aiInsights)
                .suggestions(suggestions)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal) return (BigDecimal) val;
        if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
        try {
            String s = val.toString().trim();
            if (s.isEmpty() || s.equals("-") || s.equals("N/A")) return BigDecimal.ZERO;
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Build KPI cards from DB aggregation results (no in-memory processing)
     */
    private List<MetricResult> buildKpiFromAggregates(
            BigDecimal totalSales, BigDecimal totalQuantity, BigDecimal totalProfit,
            BigDecimal totalCost, BigDecimal totalTarget, long orderCount,
            String factoryId, LocalDate startDate, LocalDate endDate) {

        List<MetricResult> kpiCards = new ArrayList<>();

        kpiCards.add(MetricResult.builder()
                .metricCode(MetricCalculatorService.SALES_AMOUNT)
                .metricName("总销售额")
                .value(totalSales.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(totalSales))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        kpiCards.add(MetricResult.builder()
                .metricCode(MetricCalculatorService.ORDER_COUNT)
                .metricName("订单数")
                .value(new BigDecimal(orderCount))
                .formattedValue(String.format("%,d", orderCount))
                .unit("单")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        BigDecimal avgOrderValue = orderCount > 0
                ? totalSales.divide(new BigDecimal(orderCount), SCALE, ROUNDING_MODE)
                : BigDecimal.ZERO;
        kpiCards.add(MetricResult.builder()
                .metricCode(MetricCalculatorService.AVG_ORDER_VALUE)
                .metricName("客单价")
                .value(avgOrderValue.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(avgOrderValue))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        BigDecimal completionRate = calculateCompletionRate(totalSales, totalTarget);
        String completionAlertLevel = determineCompletionAlertLevel(factoryId, completionRate);
        kpiCards.add(MetricResult.builder()
                .metricCode(MetricCalculatorService.TARGET_COMPLETION)
                .metricName("目标完成率")
                .value(completionRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", completionRate.doubleValue()))
                .unit("%")
                .alertLevel(completionAlertLevel)
                .build());

        // 环比: use aggregation query for previous period too
        Object[] prevSummary = salesDataRepository.findKpiSummary(
                factoryId, startDate.minusMonths(1), endDate.minusMonths(1));
        if (prevSummary != null && prevSummary.length == 1 && prevSummary[0] instanceof Object[]) {
            prevSummary = (Object[]) prevSummary[0];
        }
        BigDecimal previousSales = (prevSummary != null && prevSummary.length > 0)
                ? toBigDecimal(prevSummary[0]) : BigDecimal.ZERO;
        if (previousSales.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal momGrowth = metricCalculatorService.calculateMomGrowth(totalSales, previousSales);
            kpiCards.add(MetricResult.builder()
                    .metricCode(MetricCalculatorService.MOM_GROWTH)
                    .metricName("环比增长")
                    .value(momGrowth.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .formattedValue(String.format("%+.1f%%", momGrowth.doubleValue()))
                    .unit("%")
                    .changePercent(momGrowth)
                    .changeDirection(determineChangeDirection(momGrowth))
                    .alertLevel(determineGrowthAlertLevel(factoryId, momGrowth))
                    .build());
        }

        return kpiCards;
    }

    /**
     * Build trend line chart from daily aggregation query results
     */
    private ChartConfig buildTrendChartFromAggregates(List<Object[]> dailyTrend) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : dailyTrend) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", row[0] != null ? row[0].toString() : "");
            point.put("amount", toBigDecimal(row[1]).setScale(DISPLAY_SCALE, ROUNDING_MODE));
            point.put("quantity", toBigDecimal(row[2]).setScale(0, ROUNDING_MODE));
            data.add(point);
        }
        return ChartConfig.builder()
                .chartType("LINE")
                .title("销售趋势")
                .xAxisField("date")
                .yAxisField("amount")
                .data(data)
                .build();
    }

    /**
     * Build pie chart from category aggregation query results
     */
    private ChartConfig buildPieChartFromAggregates(List<Object[]> categoryData) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : categoryData) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("category", row[0] != null ? row[0].toString() : "未分类");
            point.put("amount", toBigDecimal(row[1]).setScale(DISPLAY_SCALE, ROUNDING_MODE));
            data.add(point);
        }
        return ChartConfig.builder()
                .chartType("PIE")
                .title("产品分布")
                .xAxisField("category")
                .yAxisField("amount")
                .data(data)
                .build();
    }

    /**
     * Build rankings from aggregation query results
     */
    private List<RankingItem> buildRankingsFromAggregates(List<Object[]> salespersonData) {
        List<RankingItem> rankings = new ArrayList<>();
        int rank = 1;
        for (Object[] row : salespersonData) {
            if (row[0] == null) continue;
            BigDecimal amount = toBigDecimal(row[1]);
            rankings.add(RankingItem.builder()
                    .rank(rank++)
                    .name(row[0].toString())
                    .value(amount.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .build());
            if (rank > 10) break; // Top 10 only
        }
        return rankings;
    }

    /**
     * Generate insights from aggregated metrics (no full data load needed)
     */
    private List<AIInsight> generateAiInsightsFromMetrics(List<MetricResult> metrics,
                                                           BigDecimal totalSales,
                                                           BigDecimal totalProfit,
                                                           long orderCount) {
        List<AIInsight> insights = new ArrayList<>();
        insights.add(AIInsight.builder()
                .level("INFO")
                .category("销售概况")
                .message(String.format("期间总销售额 %s，共 %,d 笔订单，总利润 %s",
                        formatCurrency(totalSales), orderCount, formatCurrency(totalProfit)))
                .build());

        if (totalSales.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal profitRate = totalProfit.multiply(new BigDecimal("100"))
                    .divide(totalSales, SCALE, ROUNDING_MODE);
            insights.add(AIInsight.builder()
                    .level("INFO")
                    .category("利润率分析")
                    .message(String.format("综合利润率 %.1f%%", profitRate.doubleValue()))
                    .build());
        }
        return insights;
    }

    /**
     * Generate suggestions from aggregated metrics
     */
    private List<String> generateSuggestionsFromMetrics(List<MetricResult> metrics,
                                                         BigDecimal totalSales,
                                                         BigDecimal totalTarget) {
        List<String> suggestions = new ArrayList<>();
        BigDecimal completionRate = calculateCompletionRate(totalSales, totalTarget);
        if (completionRate.compareTo(new BigDecimal("80")) < 0 && totalTarget.compareTo(BigDecimal.ZERO) > 0) {
            suggestions.add("目标完成率不足80%，建议加强销售推进");
        }
        return suggestions;
    }

    // ==================== 销售员分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getSalespersonRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取销售员排名: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<Object[]> results = salesDataRepository.findSalesBySalesperson(factoryId, startDate, endDate);
        List<RankingItem> rankings = new ArrayList<>();

        // 获取目标数据用于计算完成率
        List<SmartBiSalesData> salesData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, startDate, endDate);
        Map<String, BigDecimal> targetMap = calculateSalespersonTargets(salesData);

        int rank = 1;
        for (Object[] row : results) {
            String salespersonName = (String) row[0];
            BigDecimal amount = toBigDecimal(row[1]);
            BigDecimal target = targetMap.getOrDefault(salespersonName, BigDecimal.ZERO);
            BigDecimal completionRate = calculateCompletionRate(amount, target);

            rankings.add(RankingItem.builder()
                    .rank(rank++)
                    .name(salespersonName)
                    .value(amount.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .target(target.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .completionRate(completionRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(determineCompletionAlertLevel(factoryId, completionRate))
                    .build());
        }

        return rankings;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getSalespersonMetrics(String factoryId, String salespersonName,
                                                     LocalDate startDate, LocalDate endDate) {
        log.info("获取销售员指标: factoryId={}, salesperson={}, startDate={}, endDate={}",
                factoryId, salespersonName, startDate, endDate);

        List<SmartBiSalesData> salesData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, startDate, endDate)
                .stream()
                .filter(d -> salespersonName.equals(d.getSalespersonName()))
                .collect(Collectors.toList());

        if (salesData.isEmpty()) {
            log.warn("未找到销售员数据: salesperson={}", salespersonName);
            return Collections.emptyList();
        }

        List<MetricResult> metrics = new ArrayList<>();

        // 销售额
        BigDecimal totalAmount = sumField(salesData, SmartBiSalesData::getAmount);
        metrics.add(MetricResult.of(MetricCalculatorService.SALES_AMOUNT, "销售额", totalAmount, "元"));

        // 订单数
        long orderCount = salesData.stream()
                .map(SmartBiSalesData::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        metrics.add(MetricResult.of(MetricCalculatorService.ORDER_COUNT, "订单数",
                new BigDecimal(orderCount), "单"));

        // 客单价
        BigDecimal avgOrderValue = orderCount > 0
                ? totalAmount.divide(new BigDecimal(orderCount), SCALE, ROUNDING_MODE)
                : BigDecimal.ZERO;
        metrics.add(MetricResult.of(MetricCalculatorService.AVG_ORDER_VALUE, "客单价", avgOrderValue, "元"));

        // 目标完成率
        BigDecimal totalTarget = sumField(salesData, SmartBiSalesData::getMonthlyTarget);
        BigDecimal completionRate = calculateCompletionRate(totalAmount, totalTarget);
        metrics.add(MetricResult.of(MetricCalculatorService.TARGET_COMPLETION, "目标完成率",
                completionRate, "%", determineCompletionAlertLevelEnum(factoryId, completionRate)));

        // 客户数
        long customerCount = salesData.stream()
                .map(SmartBiSalesData::getCustomerName)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        metrics.add(MetricResult.of(MetricCalculatorService.CUSTOMER_COUNT, "客户数",
                new BigDecimal(customerCount), "个"));

        // 毛利额
        BigDecimal totalCost = sumField(salesData, SmartBiSalesData::getCost);
        BigDecimal grossProfit = totalAmount.subtract(totalCost);
        metrics.add(MetricResult.of(MetricCalculatorService.GROSS_PROFIT, "毛利额", grossProfit, "元"));

        // 毛利率
        BigDecimal grossMargin = totalAmount.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.divide(totalAmount, SCALE, ROUNDING_MODE).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        metrics.add(MetricResult.of(MetricCalculatorService.GROSS_MARGIN, "毛利率",
                grossMargin, "%", determineMarginAlertLevelEnum(factoryId, grossMargin)));

        // 计算环比增长（需要上一期数据）
        LocalDate previousStart = startDate.minusMonths(1);
        LocalDate previousEnd = endDate.minusMonths(1);
        List<SmartBiSalesData> previousData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, previousStart, previousEnd)
                .stream()
                .filter(d -> salespersonName.equals(d.getSalespersonName()))
                .collect(Collectors.toList());

        if (!previousData.isEmpty()) {
            BigDecimal previousAmount = sumField(previousData, SmartBiSalesData::getAmount);
            BigDecimal momGrowth = metricCalculatorService.calculateMomGrowth(totalAmount, previousAmount);
            metrics.add(MetricResult.ofWithTrend(MetricCalculatorService.MOM_GROWTH, "环比增长",
                    momGrowth, "%", momGrowth, determineChangeDirection(momGrowth)));
        }

        return metrics;
    }

    // ==================== 产品分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getProductRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取产品排名: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<SmartBiSalesData> salesData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, startDate, endDate);

        // 按产品类别分组统计
        Map<String, BigDecimal> productSales = salesData.stream()
                .filter(d -> d.getProductCategory() != null)
                .collect(Collectors.groupingBy(
                        SmartBiSalesData::getProductCategory,
                        Collectors.reducing(BigDecimal.ZERO,
                                SmartBiSalesData::getAmount,
                                BigDecimal::add)
                ));

        BigDecimal totalSales = productSales.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RankingItem> rankings = new ArrayList<>();
        int rank = 1;

        List<Map.Entry<String, BigDecimal>> sortedProducts = productSales.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .collect(Collectors.toList());

        for (Map.Entry<String, BigDecimal> entry : sortedProducts) {
            BigDecimal percentage = totalSales.compareTo(BigDecimal.ZERO) > 0
                    ? entry.getValue().divide(totalSales, SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            rankings.add(RankingItem.builder()
                    .rank(rank++)
                    .name(entry.getKey())
                    .value(entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .completionRate(percentage.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(MetricResult.AlertLevel.GREEN.name())
                    .build());
        }

        return rankings;
    }

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getProductDistributionChart(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取产品分布图表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<SmartBiSalesData> salesData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, startDate, endDate);

        return buildProductPieChart(salesData);
    }

    // ==================== 客户分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getCustomerRanking(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取客户排名: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<SmartBiSalesData> salesData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, startDate, endDate);

        // 按客户分组统计
        Map<String, BigDecimal> customerSales = salesData.stream()
                .filter(d -> d.getCustomerName() != null)
                .collect(Collectors.groupingBy(
                        SmartBiSalesData::getCustomerName,
                        Collectors.reducing(BigDecimal.ZERO,
                                SmartBiSalesData::getAmount,
                                BigDecimal::add)
                ));

        BigDecimal totalSales = customerSales.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RankingItem> rankings = new ArrayList<>();
        int rank = 1;

        List<Map.Entry<String, BigDecimal>> sortedCustomers = customerSales.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10) // Top 10
                .collect(Collectors.toList());

        for (Map.Entry<String, BigDecimal> entry : sortedCustomers) {
            BigDecimal percentage = totalSales.compareTo(BigDecimal.ZERO) > 0
                    ? entry.getValue().divide(totalSales, SCALE, ROUNDING_MODE)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            rankings.add(RankingItem.builder()
                    .rank(rank++)
                    .name(entry.getKey())
                    .value(entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .completionRate(percentage.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(MetricResult.AlertLevel.GREEN.name())
                    .build());
        }

        return rankings;
    }

    // ==================== 趋势分析 ====================

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getSalesTrendChart(String factoryId, LocalDate startDate, LocalDate endDate, String period) {
        log.info("获取销售趋势图表: factoryId={}, startDate={}, endDate={}, period={}",
                factoryId, startDate, endDate, period);

        List<SmartBiSalesData> salesData = salesDataRepository
                .findByFactoryIdAndOrderDateBetween(factoryId, startDate, endDate);

        return buildSalesTrendChartFromData(salesData, period);
    }

    // ==================== 私有辅助方法 ====================

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
     * 计算销售员目标
     */
    private Map<String, BigDecimal> calculateSalespersonTargets(List<SmartBiSalesData> salesData) {
        return salesData.stream()
                .filter(d -> d.getSalespersonName() != null)
                .collect(Collectors.groupingBy(
                        SmartBiSalesData::getSalespersonName,
                        Collectors.reducing(BigDecimal.ZERO,
                                d -> d.getMonthlyTarget() != null ? d.getMonthlyTarget() : BigDecimal.ZERO,
                                BigDecimal::add)
                ));
    }

    /**
     * 构建销售趋势图表
     */
    private ChartConfig buildSalesTrendChartFromData(List<SmartBiSalesData> salesData, String period) {
        Map<String, BigDecimal> periodSales;

        switch (period.toUpperCase()) {
            case "WEEK":
                periodSales = aggregateByWeek(salesData);
                break;
            case "MONTH":
                periodSales = aggregateByMonth(salesData);
                break;
            case "DAY":
            default:
                periodSales = aggregateByDay(salesData);
                break;
        }

        List<Map<String, Object>> chartData = periodSales.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> dataPoint = new LinkedHashMap<>();
                    dataPoint.put("date", entry.getKey());
                    dataPoint.put("amount", entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE));
                    return dataPoint;
                })
                .collect(Collectors.toList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showDataLabels", false);
        options.put("smooth", true);

        return ChartConfig.builder()
                .chartType("LINE")
                .title("销售趋势")
                .xAxisField("date")
                .yAxisField("amount")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 按日聚合
     */
    private Map<String, BigDecimal> aggregateByDay(List<SmartBiSalesData> salesData) {
        return salesData.stream()
                .filter(d -> d.getOrderDate() != null)
                .collect(Collectors.groupingBy(
                        d -> d.getOrderDate().toString(),
                        TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                SmartBiSalesData::getAmount,
                                BigDecimal::add)
                ));
    }

    /**
     * 按周聚合
     */
    private Map<String, BigDecimal> aggregateByWeek(List<SmartBiSalesData> salesData) {
        return salesData.stream()
                .filter(d -> d.getOrderDate() != null)
                .collect(Collectors.groupingBy(
                        d -> {
                            LocalDate date = d.getOrderDate();
                            LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                            return weekStart.toString();
                        },
                        TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                SmartBiSalesData::getAmount,
                                BigDecimal::add)
                ));
    }

    /**
     * 按月聚合
     */
    private Map<String, BigDecimal> aggregateByMonth(List<SmartBiSalesData> salesData) {
        return salesData.stream()
                .filter(d -> d.getOrderDate() != null)
                .collect(Collectors.groupingBy(
                        d -> d.getOrderDate().getYear() + "-" +
                                String.format("%02d", d.getOrderDate().getMonthValue()),
                        TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                SmartBiSalesData::getAmount,
                                BigDecimal::add)
                ));
    }

    /**
     * 构建产品饼图
     */
    private ChartConfig buildProductPieChart(List<SmartBiSalesData> salesData) {
        Map<String, BigDecimal> productSales = salesData.stream()
                .filter(d -> d.getProductCategory() != null)
                .collect(Collectors.groupingBy(
                        SmartBiSalesData::getProductCategory,
                        Collectors.reducing(BigDecimal.ZERO,
                                SmartBiSalesData::getAmount,
                                BigDecimal::add)
                ));

        List<Map<String, Object>> chartData = productSales.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> dataPoint = new LinkedHashMap<>();
                    dataPoint.put("category", entry.getKey());
                    dataPoint.put("amount", entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE));
                    return dataPoint;
                })
                .collect(Collectors.toList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showPercentage", true);
        options.put("showLegend", true);

        return ChartConfig.builder()
                .chartType("PIE")
                .title("产品销售占比")
                .xAxisField("category")
                .yAxisField("amount")
                .data(chartData)
                .options(options)
                .build();
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
                        .message("当前时间范围内暂无销售数据")
                        .actionSuggestion("请上传销售数据或调整时间范围")
                        .build()))
                .suggestions(Collections.singletonList("请先上传销售数据以开始分析"))
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 工具方法 ====================

    /**
     * 计算完成率
     */
    private BigDecimal calculateCompletionRate(BigDecimal actual, BigDecimal target) {
        if (target == null || target.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return actual.divide(target, SCALE, ROUNDING_MODE).multiply(new BigDecimal("100"));
    }

    /**
     * 确定完成率预警级别
     */
    private String determineCompletionAlertLevel(String factoryId, BigDecimal completionRate) {
        if (completionRate.compareTo(resolveTargetRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (completionRate.compareTo(resolveTargetYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    /**
     * 确定完成率预警级别枚举
     */
    private MetricResult.AlertLevel determineCompletionAlertLevelEnum(String factoryId, BigDecimal completionRate) {
        if (completionRate.compareTo(resolveTargetRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED;
        }
        if (completionRate.compareTo(resolveTargetYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW;
        }
        return MetricResult.AlertLevel.GREEN;
    }

    /**
     * 确定毛利率预警级别枚举
     */
    private MetricResult.AlertLevel determineMarginAlertLevelEnum(String factoryId, BigDecimal margin) {
        if (margin.compareTo(resolveMarginRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED;
        }
        if (margin.compareTo(resolveMarginYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW;
        }
        return MetricResult.AlertLevel.GREEN;
    }

    /**
     * 确定增长率预警级别
     */
    private String determineGrowthAlertLevel(String factoryId, BigDecimal growth) {
        if (growth.compareTo(resolveGrowthRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED.name();
        }
        if (growth.compareTo(resolveGrowthYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW.name();
        }
        return MetricResult.AlertLevel.GREEN.name();
    }

    /**
     * 确定变化方向
     */
    private String determineChangeDirection(BigDecimal changePercent) {
        if (changePercent == null) {
            return "STABLE";
        }
        int cmp = changePercent.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            return "UP";
        } else if (cmp < 0) {
            return "DOWN";
        }
        return "STABLE";
    }

    /**
     * 字段求和
     */
    private BigDecimal sumField(List<SmartBiSalesData> data,
                                 java.util.function.Function<SmartBiSalesData, BigDecimal> extractor) {
        return data.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 格式化货币
     */
    private String formatCurrency(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return String.format("%,.2f", value.setScale(DISPLAY_SCALE, ROUNDING_MODE).doubleValue());
    }
}
