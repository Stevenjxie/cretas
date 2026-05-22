package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.AIInsight;
import com.cretas.aims.dto.smartbi.ChartConfig;
import com.cretas.aims.dto.smartbi.DashboardResponse;
import com.cretas.aims.dto.smartbi.KPICard;
import com.cretas.aims.dto.smartbi.MetricResult;
import com.cretas.aims.dto.smartbi.RankingItem;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.smartbi.InventoryHealthAnalysisService;
import com.cretas.aims.service.smartbi.MetricCalculatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 库存健康分析服务实现
 *
 * 实现 SmartBI 系统中库存健康相关的分析，包括：
 * - 库存健康概览：KPI 卡片、图表、排名、AI 洞察
 * - 周转分析：周转率、库存天数
 * - 临期风险分析：临期库存、过期预警
 * - 损耗分析：损耗金额、损耗率
 * - 库龄分析：库龄分布
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
public class InventoryHealthAnalysisServiceImpl implements InventoryHealthAnalysisService {

    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final MaterialBatchAdjustmentRepository adjustmentRepository;
    private final MetricCalculatorService metricCalculatorService;
    /**
     * Canvas-Thresholds resolver (Phase A) — overlays the FALLBACK_* defaults below with
     * per-factory configured values from {@code factory_thresholds} table. Hard-coded
     * fallbacks remain as the last-resort default if no DB row + no global default exists,
     * so the service still runs correctly even before the migration is applied.
     */
    private final ThresholdResolverService thresholdResolver;

    // 计算精度配置
    private static final int SCALE = 4;
    private static final int DISPLAY_SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    // ==================== 预警阈值 fallback 默认值 ====================
    // 以下 static final 常量是 hard-coded baseline; 实际取值通过 thresholdXxx(factoryId)
    // helpers 从 ThresholdResolverService 读取, 仅 DB row 不存在时 fallback 到这里.

    /** 周转率红色预警 fallback 默认值（次/年）*/
    private static final BigDecimal FALLBACK_TURNOVER_RED = new BigDecimal("6");
    /** 周转率黄色预警 fallback 默认值（次/年）*/
    private static final BigDecimal FALLBACK_TURNOVER_YELLOW = new BigDecimal("12");
    /** 临期风险率红色预警 fallback 默认值 */
    private static final BigDecimal FALLBACK_EXPIRY_RED = new BigDecimal("15");
    /** 临期风险率黄色预警 fallback 默认值 */
    private static final BigDecimal FALLBACK_EXPIRY_YELLOW = new BigDecimal("10");
    /** 损耗率红色预警 fallback 默认值 */
    private static final BigDecimal FALLBACK_LOSS_RED = new BigDecimal("5");
    /** 损耗率黄色预警 fallback 默认值 */
    private static final BigDecimal FALLBACK_LOSS_YELLOW = new BigDecimal("2");

    // 库龄分段 fallback 默认值（天）
    private static final int FALLBACK_AGING_FRESH = 30;
    private static final int FALLBACK_AGING_NORMAL = 60;
    private static final int FALLBACK_AGING_WARNING = 90;

    // 临期预警 fallback 默认值（天）
    private static final int FALLBACK_DEFAULT_EXPIRY_WARNING_DAYS = 30;
    private static final int FALLBACK_HIGH_RISK_EXPIRY_DAYS = 7;

    // ==================== Threshold resolver shortcuts ====================
    // 这些方法把"读 DB 阈值 + fallback default"封装为一行调用, 业务代码 readable.

    private BigDecimal turnoverRedThreshold(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.INVENTORY_TURNOVER_RED, FALLBACK_TURNOVER_RED);
    }
    private BigDecimal turnoverYellowThreshold(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.INVENTORY_TURNOVER_YELLOW, FALLBACK_TURNOVER_YELLOW);
    }
    private BigDecimal expiryRedThreshold(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.INVENTORY_EXPIRY_RED, FALLBACK_EXPIRY_RED);
    }
    private BigDecimal expiryYellowThreshold(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.INVENTORY_EXPIRY_YELLOW, FALLBACK_EXPIRY_YELLOW);
    }
    private BigDecimal lossRedThreshold(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.INVENTORY_LOSS_RED, FALLBACK_LOSS_RED);
    }
    private BigDecimal lossYellowThreshold(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.INVENTORY_LOSS_YELLOW, FALLBACK_LOSS_YELLOW);
    }
    private int agingFreshDays(String factoryId) {
        return thresholdResolver.getInteger(factoryId, ThresholdKeys.INVENTORY_AGING_FRESH, FALLBACK_AGING_FRESH);
    }
    private int agingNormalDays(String factoryId) {
        return thresholdResolver.getInteger(factoryId, ThresholdKeys.INVENTORY_AGING_NORMAL, FALLBACK_AGING_NORMAL);
    }
    private int agingWarningDays(String factoryId) {
        return thresholdResolver.getInteger(factoryId, ThresholdKeys.INVENTORY_AGING_WARNING, FALLBACK_AGING_WARNING);
    }
    private int expiryWarningDays(String factoryId) {
        return thresholdResolver.getInteger(factoryId, ThresholdKeys.INVENTORY_EXPIRY_WARNING_DAYS, FALLBACK_DEFAULT_EXPIRY_WARNING_DAYS);
    }
    private int highRiskExpiryDays(String factoryId) {
        return thresholdResolver.getInteger(factoryId, ThresholdKeys.INVENTORY_HIGH_RISK_EXPIRY_DAYS, FALLBACK_HIGH_RISK_EXPIRY_DAYS);
    }

    // ==================== 库存健康概览 ====================

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getInventoryHealth(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取库存健康概览: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // 获取所有可用批次
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);

        if (allBatches.isEmpty()) {
            log.warn("未找到库存数据: factoryId={}", factoryId);
            return buildEmptyDashboard();
        }

        // 计算 KPI 卡片
        List<MetricResult> metricResults = calculateKpiCards(allBatches, factoryId, startDate, endDate);
        List<KPICard> kpiCards = convertToKPICards(metricResults);

        // 生成图表
        List<ChartConfig> chartList = new ArrayList<>();
        chartList.add(getInventoryAgingChart(factoryId));
        chartList.add(getExpiryRiskChart(factoryId));
        chartList.add(buildMaterialCategoryValueChart(allBatches));
        Map<String, ChartConfig> charts = new LinkedHashMap<>();
        for (ChartConfig chart : chartList) {
            charts.put(chart.getTitle() != null ? chart.getTitle().replace(" ", "_") : "chart_" + charts.size(), chart);
        }

        // 生成排名
        List<RankingItem> expiringRanking = getExpiringBatchesRanking(factoryId, expiryWarningDays(factoryId));
        List<RankingItem> agingRanking = getLongAgingBatchesRanking(factoryId, agingNormalDays(factoryId));
        Map<String, List<RankingItem>> rankings = new LinkedHashMap<>();
        rankings.put("expiring", expiringRanking);
        rankings.put("aging", agingRanking);

        // 生成 AI 洞察
        List<AIInsight> aiInsights = generateAiInsights(allBatches, metricResults, factoryId);

        // 生成建议
        List<String> suggestions = generateSuggestions(allBatches, metricResults, factoryId);

        return DashboardResponse.builder()
                .kpiCards(kpiCards)
                .charts(charts)
                .rankings(rankings)
                .aiInsights(aiInsights)
                .suggestions(suggestions)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 周转分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getTurnoverAnalysis(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取周转分析: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<MetricResult> metrics = new ArrayList<>();

        // 获取当前库存价值
        BigDecimal currentInventoryValue = materialBatchRepository.calculateInventoryValue(factoryId);
        if (currentInventoryValue == null) {
            currentInventoryValue = BigDecimal.ZERO;
        }

        // 获取期间消耗量
        List<MaterialConsumption> consumptions = materialConsumptionRepository.findByTimeRange(
                factoryId,
                startDate.atStartOfDay(),
                endDate.atTime(23, 59, 59));

        BigDecimal totalConsumption = consumptions.stream()
                .map(MaterialConsumption::getTotalCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算周转率 = 年化消耗量 / 平均库存
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        BigDecimal annualizedConsumption = totalConsumption
                .multiply(new BigDecimal("365"))
                .divide(new BigDecimal(daysBetween), SCALE, ROUNDING_MODE);

        BigDecimal turnoverRate = currentInventoryValue.compareTo(BigDecimal.ZERO) > 0
                ? annualizedConsumption.divide(currentInventoryValue, SCALE, ROUNDING_MODE)
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode(TURNOVER_RATE)
                .metricName("库存周转率")
                .value(turnoverRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f 次/年", turnoverRate.doubleValue()))
                .unit("次/年")
                .alertLevel(determineTurnoverAlertLevel(factoryId, turnoverRate).name())
                .build());

        // 计算库存天数 = 365 / 周转率
        BigDecimal inventoryDays = turnoverRate.compareTo(BigDecimal.ZERO) > 0
                ? new BigDecimal("365").divide(turnoverRate, SCALE, ROUNDING_MODE)
                : new BigDecimal("999");

        metrics.add(MetricResult.builder()
                .metricCode(INVENTORY_DAYS)
                .metricName("库存天数")
                .value(inventoryDays.setScale(0, ROUNDING_MODE))
                .formattedValue(String.format("%.0f 天", inventoryDays.doubleValue()))
                .unit("天")
                .alertLevel(determineInventoryDaysAlertLevel(inventoryDays).name())
                .build());

        // 期间消耗总额
        metrics.add(MetricResult.of("CONSUMPTION_AMOUNT", "期间消耗", totalConsumption, "元"));

        // 当前库存价值
        metrics.add(MetricResult.of(INVENTORY_VALUE, "库存价值", currentInventoryValue, "元"));

        return metrics;
    }

    // ==================== 临期风险分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getExpiryRiskAnalysis(String factoryId) {
        log.info("获取临期风险分析: factoryId={}", factoryId);

        List<MetricResult> metrics = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate warningDate = today.plusDays(expiryWarningDays(factoryId));
        LocalDate highRiskDate = today.plusDays(highRiskExpiryDays(factoryId));

        // 获取所有可用批次
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);

        // 获取临期批次（30天内过期）
        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatches(factoryId, warningDate);

        // 获取高风险批次（7天内过期）
        List<MaterialBatch> highRiskBatches = expiringBatches.stream()
                .filter(b -> b.getExpireDate() != null && !b.getExpireDate().isAfter(highRiskDate))
                .collect(Collectors.toList());

        // 获取已过期批次
        List<MaterialBatch> expiredBatches = materialBatchRepository.findExpiredBatches(factoryId);

        // 计算临期风险率
        BigDecimal totalValue = calculateTotalInventoryValue(allBatches);
        BigDecimal expiringValue = calculateTotalInventoryValue(expiringBatches);
        BigDecimal expiryRiskRate = totalValue.compareTo(BigDecimal.ZERO) > 0
                ? expiringValue.divide(totalValue, SCALE, ROUNDING_MODE).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode(EXPIRY_RISK_RATE)
                .metricName("临期风险率")
                .value(expiryRiskRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", expiryRiskRate.doubleValue()))
                .unit("%")
                .alertLevel(determineExpiryRiskAlertLevel(factoryId, expiryRiskRate).name())
                .description(String.format("30天内临期库存占比"))
                .build());

        // 临期批次数
        metrics.add(MetricResult.builder()
                .metricCode(EXPIRING_COUNT)
                .metricName("临期批次数")
                .value(new BigDecimal(expiringBatches.size()))
                .formattedValue(String.format("%d 批", expiringBatches.size()))
                .unit("批")
                .alertLevel(expiringBatches.isEmpty() ? MetricResult.AlertLevel.GREEN.name() :
                        MetricResult.AlertLevel.YELLOW.name())
                .build());

        // 高风险批次数（7天内过期）
        metrics.add(MetricResult.builder()
                .metricCode("HIGH_RISK_COUNT")
                .metricName("高风险批次")
                .value(new BigDecimal(highRiskBatches.size()))
                .formattedValue(String.format("%d 批", highRiskBatches.size()))
                .unit("批")
                .alertLevel(highRiskBatches.isEmpty() ? MetricResult.AlertLevel.GREEN.name() :
                        MetricResult.AlertLevel.RED.name())
                .description("7天内过期")
                .build());

        // 已过期批次数
        metrics.add(MetricResult.builder()
                .metricCode(EXPIRED_COUNT)
                .metricName("已过期批次")
                .value(new BigDecimal(expiredBatches.size()))
                .formattedValue(String.format("%d 批", expiredBatches.size()))
                .unit("批")
                .alertLevel(expiredBatches.isEmpty() ? MetricResult.AlertLevel.GREEN.name() :
                        MetricResult.AlertLevel.RED.name())
                .build());

        // 临期库存价值
        metrics.add(MetricResult.of("EXPIRING_VALUE", "临期库存价值", expiringValue, "元"));

        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getExpiringBatchesRanking(String factoryId, int daysToExpiry) {
        log.info("获取临期批次排名: factoryId={}, daysToExpiry={}", factoryId, daysToExpiry);

        LocalDate warningDate = LocalDate.now().plusDays(daysToExpiry);
        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatches(factoryId, warningDate);

        List<RankingItem> rankings = new ArrayList<>();
        int rank = 1;

        // 按过期日期排序（最快过期的排在前面）
        List<MaterialBatch> sorted = expiringBatches.stream()
                .filter(b -> b.getExpireDate() != null)
                .sorted(Comparator.comparing(MaterialBatch::getExpireDate))
                .limit(20)
                .collect(Collectors.toList());

        LocalDate today = LocalDate.now();
        for (MaterialBatch batch : sorted) {
            long daysUntilExpiry = ChronoUnit.DAYS.between(today, batch.getExpireDate());
            BigDecimal value = batch.getCurrentQuantity().multiply(
                    batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO);

            String alertLevel;
            if (daysUntilExpiry <= highRiskExpiryDays(factoryId)) {
                alertLevel = MetricResult.AlertLevel.RED.name();
            } else if (daysUntilExpiry <= 15) {
                alertLevel = MetricResult.AlertLevel.YELLOW.name();
            } else {
                alertLevel = MetricResult.AlertLevel.GREEN.name();
            }

            rankings.add(RankingItem.builder()
                    .rank(rank++)
                    .name(batch.getBatchNumber())
                    .value(value.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .target(new BigDecimal(daysUntilExpiry))
                    .completionRate(batch.getCurrentQuantity().setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(alertLevel)
                    .build());
        }

        return rankings;
    }

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getExpiryRiskChart(String factoryId) {
        log.info("获取临期风险图表: factoryId={}", factoryId);

        LocalDate today = LocalDate.now();
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);

        // 按临期状态分组
        Map<String, BigDecimal> riskDistribution = new LinkedHashMap<>();
        riskDistribution.put("正常（>30天）", BigDecimal.ZERO);
        riskDistribution.put("关注（15-30天）", BigDecimal.ZERO);
        riskDistribution.put("预警（7-15天）", BigDecimal.ZERO);
        riskDistribution.put("紧急（<7天）", BigDecimal.ZERO);
        riskDistribution.put("无保质期", BigDecimal.ZERO);

        for (MaterialBatch batch : allBatches) {
            BigDecimal value = batch.getCurrentQuantity().multiply(
                    batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO);

            if (batch.getExpireDate() == null) {
                riskDistribution.merge("无保质期", value, BigDecimal::add);
                continue;
            }

            long daysUntilExpiry = ChronoUnit.DAYS.between(today, batch.getExpireDate());
            if (daysUntilExpiry < 7) {
                riskDistribution.merge("紧急（<7天）", value, BigDecimal::add);
            } else if (daysUntilExpiry < 15) {
                riskDistribution.merge("预警（7-15天）", value, BigDecimal::add);
            } else if (daysUntilExpiry < 30) {
                riskDistribution.merge("关注（15-30天）", value, BigDecimal::add);
            } else {
                riskDistribution.merge("正常（>30天）", value, BigDecimal::add);
            }
        }

        List<Map<String, Object>> chartData = riskDistribution.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> dataPoint = new LinkedHashMap<>();
                    dataPoint.put("status", entry.getKey());
                    dataPoint.put("value", entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE));
                    return dataPoint;
                })
                .collect(Collectors.toList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showPercentage", true);
        options.put("showLegend", true);
        options.put("colors", Arrays.asList("#52c41a", "#faad14", "#ff7a45", "#ff4d4f", "#8c8c8c"));

        return ChartConfig.builder()
                .chartType("PIE")
                .title("临期风险分布")
                .xAxisField("status")
                .yAxisField("value")
                .data(chartData)
                .options(options)
                .build();
    }

    // ==================== 损耗分析 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getLossAnalysis(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取损耗分析: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        List<MetricResult> metrics = new ArrayList<>();

        // 获取所有调整记录
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);
        BigDecimal totalInventoryValue = calculateTotalInventoryValue(allBatches);

        // 获取损耗类型的调整记录
        BigDecimal lossAmount = BigDecimal.ZERO;
        BigDecimal damageAmount = BigDecimal.ZERO;
        BigDecimal correctionAmount = BigDecimal.ZERO;

        for (MaterialBatch batch : allBatches) {
            List<MaterialBatchAdjustment> adjustments = adjustmentRepository
                    .findByMaterialBatchIdAndAdjustmentTimeBetweenOrderByAdjustmentTimeDesc(
                            batch.getId(),
                            startDate.atStartOfDay(),
                            endDate.atTime(23, 59, 59));

            for (MaterialBatchAdjustment adj : adjustments) {
                BigDecimal adjValue = adj.getAdjustmentQuantity().abs().multiply(
                        batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO);

                String type = adj.getAdjustmentType();
                if ("loss".equals(type)) {
                    lossAmount = lossAmount.add(adjValue);
                } else if ("damage".equals(type)) {
                    damageAmount = damageAmount.add(adjValue);
                } else if ("correction".equals(type) && adj.getAdjustmentQuantity().compareTo(BigDecimal.ZERO) < 0) {
                    correctionAmount = correctionAmount.add(adjValue);
                }
            }
        }

        BigDecimal totalLoss = lossAmount.add(damageAmount).add(correctionAmount);

        // 损耗金额
        metrics.add(MetricResult.of(LOSS_AMOUNT, "损耗金额", totalLoss, "元"));

        // 损耗率
        BigDecimal lossRate = totalInventoryValue.compareTo(BigDecimal.ZERO) > 0
                ? totalLoss.divide(totalInventoryValue, SCALE, ROUNDING_MODE).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode(LOSS_RATE)
                .metricName("损耗率")
                .value(lossRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.2f%%", lossRate.doubleValue()))
                .unit("%")
                .alertLevel(determineLossRateAlertLevel(factoryId, lossRate).name())
                .build());

        // 分项损耗
        metrics.add(MetricResult.of("LOSS_MISSING", "丢失金额", lossAmount, "元"));
        metrics.add(MetricResult.of("LOSS_DAMAGE", "损坏金额", damageAmount, "元"));
        metrics.add(MetricResult.of("LOSS_CORRECTION", "盘亏金额", correctionAmount, "元"));

        return metrics;
    }

    // ==================== 库龄分析 ====================

    @Override
    @Transactional(readOnly = true)
    public ChartConfig getInventoryAgingChart(String factoryId) {
        log.info("获取库龄分布图表: factoryId={}", factoryId);

        LocalDate today = LocalDate.now();
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);

        // 按库龄分组
        Map<String, BigDecimal> agingDistribution = new LinkedHashMap<>();
        agingDistribution.put("0-30天", BigDecimal.ZERO);
        agingDistribution.put("31-60天", BigDecimal.ZERO);
        agingDistribution.put("61-90天", BigDecimal.ZERO);
        agingDistribution.put("90天以上", BigDecimal.ZERO);

        for (MaterialBatch batch : allBatches) {
            BigDecimal value = batch.getCurrentQuantity().multiply(
                    batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO);

            LocalDate receiptDate = batch.getReceiptDate();
            if (receiptDate == null) {
                agingDistribution.merge("90天以上", value, BigDecimal::add);
                continue;
            }

            long ageDays = ChronoUnit.DAYS.between(receiptDate, today);
            int fresh = agingFreshDays(factoryId);
            int normal = agingNormalDays(factoryId);
            int warning = agingWarningDays(factoryId);
            if (ageDays <= fresh) {
                agingDistribution.merge("0-30天", value, BigDecimal::add);
            } else if (ageDays <= normal) {
                agingDistribution.merge("31-60天", value, BigDecimal::add);
            } else if (ageDays <= warning) {
                agingDistribution.merge("61-90天", value, BigDecimal::add);
            } else {
                agingDistribution.merge("90天以上", value, BigDecimal::add);
            }
        }

        List<Map<String, Object>> chartData = agingDistribution.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> dataPoint = new LinkedHashMap<>();
                    dataPoint.put("aging", entry.getKey());
                    dataPoint.put("value", entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE));
                    return dataPoint;
                })
                .collect(Collectors.toList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showDataLabels", true);
        options.put("colors", Arrays.asList("#52c41a", "#1890ff", "#faad14", "#ff4d4f"));

        return ChartConfig.builder()
                .chartType("BAR")
                .title("库龄分布")
                .xAxisField("aging")
                .yAxisField("value")
                .data(chartData)
                .options(options)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricResult> getAgingMetrics(String factoryId) {
        log.info("获取库龄指标: factoryId={}", factoryId);

        LocalDate today = LocalDate.now();
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);
        List<MetricResult> metrics = new ArrayList<>();

        BigDecimal totalValue = calculateTotalInventoryValue(allBatches);

        // 计算呆滞库存（>warning阈值, 默认90天以上）
        int warningDays = agingWarningDays(factoryId);
        BigDecimal slowMovingValue = allBatches.stream()
                .filter(b -> b.getReceiptDate() != null)
                .filter(b -> ChronoUnit.DAYS.between(b.getReceiptDate(), today) > warningDays)
                .map(b -> b.getCurrentQuantity().multiply(
                        b.getUnitPrice() != null ? b.getUnitPrice() : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal slowMovingRate = totalValue.compareTo(BigDecimal.ZERO) > 0
                ? slowMovingValue.divide(totalValue, SCALE, ROUNDING_MODE).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        metrics.add(MetricResult.builder()
                .metricCode(SLOW_MOVING_RATE)
                .metricName("呆滞库存率")
                .value(slowMovingRate.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(String.format("%.1f%%", slowMovingRate.doubleValue()))
                .unit("%")
                .alertLevel(slowMovingRate.compareTo(new BigDecimal("20")) > 0 ?
                        MetricResult.AlertLevel.RED.name() :
                        slowMovingRate.compareTo(new BigDecimal("10")) > 0 ?
                                MetricResult.AlertLevel.YELLOW.name() :
                                MetricResult.AlertLevel.GREEN.name())
                .description("90天以上库龄占比")
                .build());

        // 呆滞库存价值
        metrics.add(MetricResult.of("SLOW_MOVING_VALUE", "呆滞库存价值", slowMovingValue, "元"));

        // 平均库龄
        OptionalDouble avgAging = allBatches.stream()
                .filter(b -> b.getReceiptDate() != null)
                .mapToLong(b -> ChronoUnit.DAYS.between(b.getReceiptDate(), today))
                .average();

        if (avgAging.isPresent()) {
            metrics.add(MetricResult.of("AVG_AGING_DAYS", "平均库龄",
                    new BigDecimal(avgAging.getAsDouble()).setScale(0, ROUNDING_MODE), "天"));
        }

        return metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankingItem> getLongAgingBatchesRanking(String factoryId, int minDays) {
        log.info("获取长库龄批次排名: factoryId={}, minDays={}", factoryId, minDays);

        LocalDate today = LocalDate.now();
        List<MaterialBatch> allBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);

        List<RankingItem> rankings = new ArrayList<>();
        int rank = 1;

        // 筛选长库龄批次并按库龄排序
        List<MaterialBatch> longAgingBatches = allBatches.stream()
                .filter(b -> b.getReceiptDate() != null)
                .filter(b -> ChronoUnit.DAYS.between(b.getReceiptDate(), today) >= minDays)
                .sorted((a, b) -> Long.compare(
                        ChronoUnit.DAYS.between(b.getReceiptDate(), today),
                        ChronoUnit.DAYS.between(a.getReceiptDate(), today)))
                .limit(20)
                .collect(Collectors.toList());

        for (MaterialBatch batch : longAgingBatches) {
            long ageDays = ChronoUnit.DAYS.between(batch.getReceiptDate(), today);
            BigDecimal value = batch.getCurrentQuantity().multiply(
                    batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO);

            String alertLevel;
            if (ageDays > 120) {
                alertLevel = MetricResult.AlertLevel.RED.name();
            } else if (ageDays > agingWarningDays(factoryId)) {
                alertLevel = MetricResult.AlertLevel.YELLOW.name();
            } else {
                alertLevel = MetricResult.AlertLevel.GREEN.name();
            }

            rankings.add(RankingItem.builder()
                    .rank(rank++)
                    .name(batch.getBatchNumber())
                    .value(value.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .target(new BigDecimal(ageDays))
                    .completionRate(batch.getCurrentQuantity().setScale(DISPLAY_SCALE, ROUNDING_MODE))
                    .alertLevel(alertLevel)
                    .build());
        }

        return rankings;
    }

    // ==================== 综合健康评估 ====================

    @Override
    @Transactional(readOnly = true)
    public MetricResult getHealthScore(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取库存健康评分: factoryId={}", factoryId);

        BigDecimal healthScore = BigDecimal.ZERO;

        // 1. 周转健康（30分）
        List<MetricResult> turnoverMetrics = getTurnoverAnalysis(factoryId, startDate, endDate);
        MetricResult turnoverRate = turnoverMetrics.stream()
                .filter(m -> TURNOVER_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (turnoverRate != null && turnoverRate.getValue() != null) {
            BigDecimal rate = turnoverRate.getValue();
            if (rate.compareTo(turnoverYellowThreshold(factoryId)) >= 0) {
                healthScore = healthScore.add(new BigDecimal("30"));
            } else if (rate.compareTo(turnoverRedThreshold(factoryId)) >= 0) {
                healthScore = healthScore.add(new BigDecimal("20"));
            } else {
                healthScore = healthScore.add(new BigDecimal("10"));
            }
        }

        // 2. 临期风险（30分）
        List<MetricResult> expiryMetrics = getExpiryRiskAnalysis(factoryId);
        MetricResult expiryRisk = expiryMetrics.stream()
                .filter(m -> EXPIRY_RISK_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (expiryRisk != null && expiryRisk.getValue() != null) {
            BigDecimal rate = expiryRisk.getValue();
            if (rate.compareTo(expiryYellowThreshold(factoryId)) < 0) {
                healthScore = healthScore.add(new BigDecimal("30"));
            } else if (rate.compareTo(expiryRedThreshold(factoryId)) < 0) {
                healthScore = healthScore.add(new BigDecimal("20"));
            } else {
                healthScore = healthScore.add(new BigDecimal("10"));
            }
        } else {
            healthScore = healthScore.add(new BigDecimal("30"));
        }

        // 3. 损耗控制（20分）
        List<MetricResult> lossMetrics = getLossAnalysis(factoryId, startDate, endDate);
        MetricResult lossRate = lossMetrics.stream()
                .filter(m -> LOSS_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (lossRate != null && lossRate.getValue() != null) {
            BigDecimal rate = lossRate.getValue();
            if (rate.compareTo(lossYellowThreshold(factoryId)) < 0) {
                healthScore = healthScore.add(new BigDecimal("20"));
            } else if (rate.compareTo(lossRedThreshold(factoryId)) < 0) {
                healthScore = healthScore.add(new BigDecimal("12"));
            } else {
                healthScore = healthScore.add(new BigDecimal("5"));
            }
        } else {
            healthScore = healthScore.add(new BigDecimal("20"));
        }

        // 4. 库龄健康（20分）
        List<MetricResult> agingMetrics = getAgingMetrics(factoryId);
        MetricResult slowMovingRate = agingMetrics.stream()
                .filter(m -> SLOW_MOVING_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (slowMovingRate != null && slowMovingRate.getValue() != null) {
            BigDecimal rate = slowMovingRate.getValue();
            if (rate.compareTo(new BigDecimal("10")) < 0) {
                healthScore = healthScore.add(new BigDecimal("20"));
            } else if (rate.compareTo(new BigDecimal("20")) < 0) {
                healthScore = healthScore.add(new BigDecimal("12"));
            } else {
                healthScore = healthScore.add(new BigDecimal("5"));
            }
        } else {
            healthScore = healthScore.add(new BigDecimal("20"));
        }

        String alertLevel;
        if (healthScore.compareTo(new BigDecimal("80")) >= 0) {
            alertLevel = MetricResult.AlertLevel.GREEN.name();
        } else if (healthScore.compareTo(new BigDecimal("60")) >= 0) {
            alertLevel = MetricResult.AlertLevel.YELLOW.name();
        } else {
            alertLevel = MetricResult.AlertLevel.RED.name();
        }

        return MetricResult.builder()
                .metricCode(HEALTH_SCORE)
                .metricName("库存健康评分")
                .value(healthScore.setScale(0, ROUNDING_MODE))
                .formattedValue(String.format("%.0f 分", healthScore.doubleValue()))
                .unit("分")
                .alertLevel(alertLevel)
                .description("满分100分")
                .build();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 计算 KPI 卡片
     */
    private List<MetricResult> calculateKpiCards(List<MaterialBatch> batches, String factoryId,
                                                  LocalDate startDate, LocalDate endDate) {
        List<MetricResult> kpiCards = new ArrayList<>();

        // 库存总值
        BigDecimal totalValue = calculateTotalInventoryValue(batches);
        kpiCards.add(MetricResult.builder()
                .metricCode(INVENTORY_VALUE)
                .metricName("库存总值")
                .value(totalValue.setScale(DISPLAY_SCALE, ROUNDING_MODE))
                .formattedValue(formatCurrency(totalValue))
                .unit("元")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 库存批次数
        kpiCards.add(MetricResult.builder()
                .metricCode("BATCH_COUNT")
                .metricName("库存批次")
                .value(new BigDecimal(batches.size()))
                .formattedValue(String.format("%,d", batches.size()))
                .unit("批")
                .alertLevel(MetricResult.AlertLevel.GREEN.name())
                .build());

        // 周转率
        List<MetricResult> turnoverMetrics = getTurnoverAnalysis(factoryId, startDate, endDate);
        MetricResult turnover = turnoverMetrics.stream()
                .filter(m -> TURNOVER_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);
        if (turnover != null) {
            kpiCards.add(turnover);
        }

        // 临期风险率
        List<MetricResult> expiryMetrics = getExpiryRiskAnalysis(factoryId);
        MetricResult expiryRisk = expiryMetrics.stream()
                .filter(m -> EXPIRY_RISK_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);
        if (expiryRisk != null) {
            kpiCards.add(expiryRisk);
        }

        // 健康评分
        MetricResult healthScore = getHealthScore(factoryId, startDate, endDate);
        kpiCards.add(healthScore);

        return kpiCards;
    }

    /**
     * 计算库存总价值
     */
    private BigDecimal calculateTotalInventoryValue(List<MaterialBatch> batches) {
        return batches.stream()
                .map(b -> b.getCurrentQuantity().multiply(
                        b.getUnitPrice() != null ? b.getUnitPrice() : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 构建材料类别价值图表
     */
    private ChartConfig buildMaterialCategoryValueChart(List<MaterialBatch> batches) {
        Map<String, BigDecimal> categoryValues = batches.stream()
                .filter(b -> b.getMaterialTypeId() != null)
                .collect(Collectors.groupingBy(
                        MaterialBatch::getMaterialTypeId,
                        Collectors.reducing(BigDecimal.ZERO,
                                b -> b.getCurrentQuantity().multiply(
                                        b.getUnitPrice() != null ? b.getUnitPrice() : BigDecimal.ZERO),
                                BigDecimal::add)
                ));

        List<Map<String, Object>> chartData = categoryValues.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .map(entry -> {
                    Map<String, Object> dataPoint = new LinkedHashMap<>();
                    dataPoint.put("category", entry.getKey());
                    dataPoint.put("value", entry.getValue().setScale(DISPLAY_SCALE, ROUNDING_MODE));
                    return dataPoint;
                })
                .collect(Collectors.toList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showPercentage", true);
        options.put("showLegend", true);

        return ChartConfig.builder()
                .chartType("PIE")
                .title("材料类别库存占比")
                .xAxisField("category")
                .yAxisField("value")
                .data(chartData)
                .options(options)
                .build();
    }

    /**
     * 生成 AI 洞察
     */
    private List<AIInsight> generateAiInsights(List<MaterialBatch> batches, List<MetricResult> kpiCards,
                                                String factoryId) {
        List<AIInsight> insights = new ArrayList<>();

        // 检查临期风险
        MetricResult expiryRisk = kpiCards.stream()
                .filter(m -> EXPIRY_RISK_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (expiryRisk != null && expiryRisk.getValue() != null) {
            BigDecimal rate = expiryRisk.getValue();
            if (rate.compareTo(expiryRedThreshold(factoryId)) > 0) {
                insights.add(AIInsight.builder()
                        .level("RED")
                        .category("临期风险")
                        .message(String.format("临期风险率高达 %.1f%%，需要立即处理", rate.doubleValue()))
                        .actionSuggestion("建议优先消耗临期库存，考虑促销或转让处理")
                        .build());
            } else if (rate.compareTo(expiryYellowThreshold(factoryId)) > 0) {
                insights.add(AIInsight.builder()
                        .level("YELLOW")
                        .category("临期风险")
                        .message(String.format("临期风险率为 %.1f%%，需要关注", rate.doubleValue()))
                        .actionSuggestion("建议制定临期库存消化计划")
                        .build());
            }
        }

        // 检查周转率
        MetricResult turnover = kpiCards.stream()
                .filter(m -> TURNOVER_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (turnover != null && turnover.getValue() != null) {
            BigDecimal rate = turnover.getValue();
            if (rate.compareTo(turnoverRedThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("RED")
                        .category("周转效率")
                        .message(String.format("库存周转率仅 %.1f 次/年，库存积压严重", rate.doubleValue()))
                        .actionSuggestion("建议减少采购量，加快库存消化，优化安全库存设置")
                        .build());
            } else if (rate.compareTo(turnoverYellowThreshold(factoryId)) < 0) {
                insights.add(AIInsight.builder()
                        .level("YELLOW")
                        .category("周转效率")
                        .message(String.format("库存周转率 %.1f 次/年，有优化空间", rate.doubleValue()))
                        .actionSuggestion("建议优化采购批次和频率，提高周转效率")
                        .build());
            }
        }

        // 检查健康评分
        MetricResult healthScore = kpiCards.stream()
                .filter(m -> HEALTH_SCORE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (healthScore != null && healthScore.getValue() != null) {
            BigDecimal score = healthScore.getValue();
            if (score.compareTo(new BigDecimal("80")) >= 0) {
                insights.add(AIInsight.builder()
                        .level("GREEN")
                        .category("整体健康")
                        .message(String.format("库存健康评分 %.0f 分，状况良好", score.doubleValue()))
                        .actionSuggestion("继续保持当前库存管理策略")
                        .build());
            }
        }

        return insights;
    }

    /**
     * 生成建议
     */
    private List<String> generateSuggestions(List<MaterialBatch> batches, List<MetricResult> kpiCards,
                                              String factoryId) {
        List<String> suggestions = new ArrayList<>();

        // 基于临期批次数生成建议
        LocalDate warningDate = LocalDate.now().plusDays(expiryWarningDays(factoryId));
        long expiringCount = batches.stream()
                .filter(b -> b.getExpireDate() != null && !b.getExpireDate().isAfter(warningDate))
                .count();

        if (expiringCount > 0) {
            suggestions.add(String.format("有 %d 批库存将在30天内过期，建议优先安排使用", expiringCount));
        }

        // 基于长库龄批次生成建议
        LocalDate today = LocalDate.now();
        long longAgingCount = batches.stream()
                .filter(b -> b.getReceiptDate() != null)
                .filter(b -> ChronoUnit.DAYS.between(b.getReceiptDate(), today) > agingWarningDays(factoryId))
                .count();

        if (longAgingCount > 0) {
            suggestions.add(String.format("有 %d 批库存库龄超过90天，建议检查使用计划或考虑处理", longAgingCount));
        }

        // 基于低周转材料生成建议
        MetricResult turnover = kpiCards.stream()
                .filter(m -> TURNOVER_RATE.equals(m.getMetricCode()))
                .findFirst().orElse(null);

        if (turnover != null && turnover.getValue() != null
                && turnover.getValue().compareTo(turnoverYellowThreshold(factoryId)) < 0) {
            suggestions.add("库存周转率偏低，建议优化安全库存设置，减少不必要的采购");
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
                        .message("当前暂无库存数据")
                        .actionSuggestion("请先录入原材料批次数据")
                        .build()))
                .suggestions(Collections.singletonList("请先录入库存数据以开始分析"))
                .lastUpdated(LocalDateTime.now())
                .build();
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

    // ==================== 预警级别判断 ====================

    /**
     * 确定周转率预警级别
     */
    private MetricResult.AlertLevel determineTurnoverAlertLevel(String factoryId, BigDecimal turnoverRate) {
        if (turnoverRate.compareTo(turnoverRedThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.RED;
        }
        if (turnoverRate.compareTo(turnoverYellowThreshold(factoryId)) < 0) {
            return MetricResult.AlertLevel.YELLOW;
        }
        return MetricResult.AlertLevel.GREEN;
    }

    /**
     * 确定库存天数预警级别
     */
    private MetricResult.AlertLevel determineInventoryDaysAlertLevel(BigDecimal inventoryDays) {
        if (inventoryDays.compareTo(new BigDecimal("60")) > 0) {
            return MetricResult.AlertLevel.RED;
        }
        if (inventoryDays.compareTo(new BigDecimal("30")) > 0) {
            return MetricResult.AlertLevel.YELLOW;
        }
        return MetricResult.AlertLevel.GREEN;
    }

    /**
     * 确定临期风险预警级别
     */
    private MetricResult.AlertLevel determineExpiryRiskAlertLevel(String factoryId, BigDecimal expiryRiskRate) {
        if (expiryRiskRate.compareTo(expiryRedThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.RED;
        }
        if (expiryRiskRate.compareTo(expiryYellowThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.YELLOW;
        }
        return MetricResult.AlertLevel.GREEN;
    }

    /**
     * 确定损耗率预警级别
     */
    private MetricResult.AlertLevel determineLossRateAlertLevel(String factoryId, BigDecimal lossRate) {
        if (lossRate.compareTo(lossRedThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.RED;
        }
        if (lossRate.compareTo(lossYellowThreshold(factoryId)) > 0) {
            return MetricResult.AlertLevel.YELLOW;
        }
        return MetricResult.AlertLevel.GREEN;
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
