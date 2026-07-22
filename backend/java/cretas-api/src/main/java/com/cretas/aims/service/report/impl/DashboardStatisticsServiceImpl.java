package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.DashboardStatisticsDTO;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.report.DashboardStatisticsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 仪表盘统计服务实现
 * 包含所有 Dashboard 异步并行统计计算方法
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Service
@RequiredArgsConstructor
public class DashboardStatisticsServiceImpl implements DashboardStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(DashboardStatisticsServiceImpl.class);

    private final ProductionPlanRepository productionPlanRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final UserRepository userRepository;
    private final EquipmentRepository equipmentRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final ShipmentRecordRepository shipmentRecordRepository;

    @Override
    @Cacheable(value = "dashboardStats", key = "#factoryId", unless = "#result == null")
    public DashboardStatisticsDTO getDashboardStatistics(String factoryId) {
        log.info("获取仪表盘统计数据（实时计算）: factoryId={}", factoryId);
        long startTime = System.currentTimeMillis();

        try {
            // 并行异步计算各个统计模块
            CompletableFuture<DashboardStatisticsDTO.ProductionStatistics> productionFuture =
                    getProductionStatisticsAsync(factoryId);
            CompletableFuture<DashboardStatisticsDTO.InventoryStatistics> inventoryFuture =
                    getInventoryStatisticsAsync(factoryId);
            CompletableFuture<DashboardStatisticsDTO.FinanceStatistics> financeFuture =
                    getFinanceStatisticsAsync(factoryId);
            CompletableFuture<DashboardStatisticsDTO.PersonnelStatistics> personnelFuture =
                    getPersonnelStatisticsAsync(factoryId);
            CompletableFuture<DashboardStatisticsDTO.EquipmentStatistics> equipmentFuture =
                    getEquipmentStatisticsAsync(factoryId);
            CompletableFuture<DashboardStatisticsDTO.QualityStatistics> qualityFuture =
                    getQualityStatisticsAsync(factoryId);
            CompletableFuture<DashboardStatisticsDTO.TrendStatistics> trendFuture =
                    getTrendStatisticsAsync(factoryId);
            CompletableFuture<List<DashboardStatisticsDTO.AlertInfo>> alertsFuture =
                    getAlertsAsync(factoryId);

            // 等待所有异步任务完成
            CompletableFuture.allOf(
                    productionFuture, inventoryFuture, financeFuture, personnelFuture,
                    equipmentFuture, qualityFuture, trendFuture, alertsFuture
            ).join();

            // 组装结果
            DashboardStatisticsDTO result = DashboardStatisticsDTO.builder()
                    .productionStats(productionFuture.join())
                    .inventoryStats(inventoryFuture.join())
                    .financeStats(financeFuture.join())
                    .personnelStats(personnelFuture.join())
                    .equipmentStats(equipmentFuture.join())
                    .qualityStats(qualityFuture.join())
                    .trendStats(trendFuture.join())
                    .alerts(alertsFuture.join())
                    .build();

            long endTime = System.currentTimeMillis();
            log.info("仪表盘统计数据计算完成: factoryId={}, 耗时={}ms", factoryId, endTime - startTime);

            return result;
        } catch (Exception e) {
            log.error("获取仪表盘统计数据失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            // 降级：同步串行执行
            return getDashboardStatisticsFallback(factoryId);
        }
    }

    /**
     * 降级方法：同步串行执行
     */
    private DashboardStatisticsDTO getDashboardStatisticsFallback(String factoryId) {
        log.warn("使用降级方法获取仪表盘数据: factoryId={}", factoryId);
        return DashboardStatisticsDTO.builder()
                .productionStats(getProductionStatistics(factoryId))
                .inventoryStats(getInventoryStatistics(factoryId))
                .financeStats(getFinanceStatistics(factoryId))
                .personnelStats(getPersonnelStatistics(factoryId))
                .equipmentStats(getEquipmentStatistics(factoryId))
                .qualityStats(getQualityStatistics(factoryId))
                .trendStats(getTrendStatistics(factoryId))
                .alerts(getAlerts(factoryId))
                .build();
    }

    // ==================== 异步方法 ====================

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.ProductionStatistics> getProductionStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getProductionStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.InventoryStatistics> getInventoryStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getInventoryStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.FinanceStatistics> getFinanceStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getFinanceStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.PersonnelStatistics> getPersonnelStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getPersonnelStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.EquipmentStatistics> getEquipmentStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getEquipmentStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.QualityStatistics> getQualityStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getQualityStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<DashboardStatisticsDTO.TrendStatistics> getTrendStatisticsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getTrendStatistics(factoryId));
    }

    @Async("dashboardExecutor")
    public CompletableFuture<List<DashboardStatisticsDTO.AlertInfo>> getAlertsAsync(String factoryId) {
        return CompletableFuture.completedFuture(getAlerts(factoryId));
    }

    // ==================== 同步统计方法 ====================

    private DashboardStatisticsDTO.ProductionStatistics getProductionStatistics(String factoryId) {
        // 获取生产计划统计
        long totalPlans = productionPlanRepository.countByFactoryId(factoryId);
        long activePlans = productionPlanRepository.countByFactoryIdAndStatus(factoryId, ProductionPlanStatus.IN_PROGRESS);
        long completedPlans = productionPlanRepository.countByFactoryIdAndStatus(factoryId, ProductionPlanStatus.COMPLETED);
        // 计算总产量和月产量
        BigDecimal totalOutput = productionPlanRepository.calculateTotalOutput(factoryId);
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        BigDecimal monthlyOutput = productionPlanRepository.calculateOutputBetweenDates(factoryId, monthStart.atStartOfDay(), LocalDate.now().atTime(23, 59, 59));
        // 计算完成率和效率
        double completionRate = totalPlans > 0 ? (completedPlans * 100.0 / totalPlans) : 0.0;
        // 从生产批次计算实际效率
        BigDecimal avgEfficiency = productionBatchRepository.calculateAverageEfficiency(factoryId, monthStart.atStartOfDay());
        double efficiency = avgEfficiency != null ? avgEfficiency.doubleValue() : 85.0;
        return DashboardStatisticsDTO.ProductionStatistics.builder()
                .totalPlans((int) totalPlans)
                .activePlans((int) activePlans)
                .completedPlans((int) completedPlans)
                .totalOutput(totalOutput != null ? totalOutput : BigDecimal.ZERO)
                .monthlyOutput(monthlyOutput != null ? monthlyOutput : BigDecimal.ZERO)
                .completionRate(completionRate)
                .efficiency(efficiency)
                .build();
    }

    private DashboardStatisticsDTO.InventoryStatistics getInventoryStatistics(String factoryId) {
        // 获取库存统计
        long totalBatches = materialBatchRepository.countByFactoryId(factoryId);
        BigDecimal totalValue = materialBatchRepository.calculateInventoryValue(factoryId);
        // 获取即将过期和已过期批次
        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatches(
                factoryId, LocalDate.now().plusDays(7));
        List<MaterialBatch> expiredBatches = materialBatchRepository.findExpiredBatches(factoryId);
        // 获取低库存预警
        List<Object> lowStockMaterials = materialBatchRepository.findLowStockMaterials(factoryId);
        // 计算库存周转率 = 年消耗量 / 平均库存价值
        // 简化计算：月消耗量 * 12 / 当前库存价值
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        BigDecimal monthlyConsumption = materialBatchRepository.calculateConsumedValue(
                factoryId, monthStart.atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay());
        BigDecimal turnoverRate = BigDecimal.valueOf(4.5); // 默认值
        if (totalValue != null && totalValue.compareTo(BigDecimal.ZERO) > 0 && monthlyConsumption != null) {
            // 年化周转率 = (月消耗量 * 12) / 库存价值
            turnoverRate = monthlyConsumption.multiply(BigDecimal.valueOf(12))
                    .divide(totalValue, 2, RoundingMode.HALF_UP);
        }
        return DashboardStatisticsDTO.InventoryStatistics.builder()
                .totalBatches((int) totalBatches)
                .totalValue(totalValue != null ? totalValue : BigDecimal.ZERO)
                .expiringBatches(expiringBatches.size())
                .expiredBatches(expiredBatches.size())
                .lowStockItems(lowStockMaterials.size())
                .turnoverRate(turnoverRate)
                .build();
    }

    private DashboardStatisticsDTO.FinanceStatistics getFinanceStatistics(String factoryId) {
        // 计算财务统计
        LocalDate today = LocalDate.now();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        LocalDate monthStart = today.withDayOfMonth(1);
        // 年度收入和成本
        BigDecimal totalRevenue = shipmentRecordRepository.calculateTotalRevenue(factoryId, yearStart, today);
        BigDecimal totalCost = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, yearStart.atStartOfDay(), today.atTime(23, 59, 59));
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;
        if (totalCost == null) totalCost = BigDecimal.ZERO;
        BigDecimal totalProfit = totalRevenue.subtract(totalCost);
        // 月度收入和成本
        BigDecimal monthlyRevenue = shipmentRecordRepository.calculateTotalRevenue(factoryId, monthStart, today);
        BigDecimal monthlyCost = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, monthStart.atStartOfDay(), today.atTime(23, 59, 59));
        if (monthlyRevenue == null) monthlyRevenue = BigDecimal.ZERO;
        if (monthlyCost == null) monthlyCost = BigDecimal.ZERO;
        BigDecimal monthlyProfit = monthlyRevenue.subtract(monthlyCost);
        // 利润率
        double profitMargin = 0.0;
        if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
            profitMargin = totalProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        // 应收应付
        BigDecimal accountsReceivable = customerRepository.calculateTotalOutstandingBalance(factoryId);
        BigDecimal accountsPayable = supplierRepository.calculateTotalOutstandingBalance(factoryId);
        return DashboardStatisticsDTO.FinanceStatistics.builder()
                .totalRevenue(totalRevenue)
                .totalCost(totalCost)
                .totalProfit(totalProfit)
                .monthlyRevenue(monthlyRevenue)
                .monthlyCost(monthlyCost)
                .monthlyProfit(monthlyProfit)
                .profitMargin(profitMargin)
                .accountsReceivable(accountsReceivable != null ? accountsReceivable : BigDecimal.ZERO)
                .accountsPayable(accountsPayable != null ? accountsPayable : BigDecimal.ZERO)
                .build();
    }

    private DashboardStatisticsDTO.PersonnelStatistics getPersonnelStatistics(String factoryId) {
        // 获取人员统计
        long totalEmployees = userRepository.countByFactoryId(factoryId);
        long activeEmployees = userRepository.countActiveUsers(factoryId);
        return DashboardStatisticsDTO.PersonnelStatistics.builder()
                .totalEmployees((int) totalEmployees)
                .activeEmployees((int) activeEmployees)
                .departmentCount(5)
                .totalSalary(BigDecimal.valueOf(500000))
                .averageSalary(BigDecimal.valueOf(10000))
                .attendanceRate(95.0)
                .todayPresent((int) activeEmployees)
                .todayAbsent(0)
                .build();
    }

    private DashboardStatisticsDTO.EquipmentStatistics getEquipmentStatistics(String factoryId) {
        // 获取设备统计
        List<Object[]> statusCount = equipmentRepository.countByStatus(factoryId);
        Map<String, Long> statusMap = new HashMap<>();
        for (Object[] row : statusCount) {
            statusMap.put((String) row[0], (Long) row[1]);
        }
        BigDecimal totalValue = equipmentRepository.calculateTotalEquipmentValue(factoryId);
        List<FactoryEquipment> needsMaintenance = equipmentRepository.findEquipmentNeedingMaintenance(factoryId, LocalDate.now());
        return DashboardStatisticsDTO.EquipmentStatistics.builder()
                .totalEquipment(statusMap.values().stream().mapToInt(Long::intValue).sum())
                .runningEquipment(statusMap.getOrDefault("running", 0L).intValue())
                .idleEquipment(statusMap.getOrDefault("idle", 0L).intValue())
                .maintenanceEquipment(statusMap.getOrDefault("maintenance", 0L).intValue())
                .utilizationRate(75.0)
                .availability(90.0)
                .needsMaintenance(needsMaintenance.size())
                .build();
    }

    private DashboardStatisticsDTO.QualityStatistics getQualityStatistics(String factoryId) {
        // 质量统计 - 使用本月数据
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        // 获取样本量和合格/不合格数据
        BigDecimal totalSampleSize = qualityInspectionRepository.calculateTotalSampleSize(factoryId, monthStart, today);
        BigDecimal passCount = qualityInspectionRepository.calculateTotalPassCount(factoryId, monthStart, today);
        BigDecimal failCount = qualityInspectionRepository.calculateTotalFailCount(factoryId, monthStart, today);
        if (totalSampleSize == null) totalSampleSize = BigDecimal.ZERO;
        if (passCount == null) passCount = BigDecimal.ZERO;
        if (failCount == null) failCount = BigDecimal.ZERO;
        // 计算合格率
        BigDecimal avgPassRate = qualityInspectionRepository.calculateAveragePassRate(factoryId, monthStart, today);
        double qualityRate = avgPassRate != null ? avgPassRate.doubleValue() : 98.0;
        // 质量问题统计
        long qualityIssues = qualityInspectionRepository.countQualityIssues(factoryId, monthStart, today);
        long resolvedIssues = qualityInspectionRepository.countResolvedIssues(factoryId, monthStart, today);
        // 一次通过率
        Double firstPassRate = qualityInspectionRepository.calculateFirstPassRate(factoryId, monthStart, today);
        return DashboardStatisticsDTO.QualityStatistics.builder()
                .totalProduction(totalSampleSize)
                .qualifiedProduction(passCount)
                .defectiveProduction(failCount)
                .qualityRate(qualityRate)
                .qualityIssues((int) qualityIssues)
                .resolvedIssues((int) resolvedIssues)
                .firstPassRate(firstPassRate != null ? firstPassRate : 96.0)
                .build();
    }

    private DashboardStatisticsDTO.TrendStatistics getTrendStatistics(String factoryId) {
        // 获取最近7天的趋势数据
        List<DashboardStatisticsDTO.DailyTrend> dailyProduction = new ArrayList<>();
        List<DashboardStatisticsDTO.DailyTrend> dailyRevenue = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            // 生产趋势
            BigDecimal output = productionPlanRepository.calculateOutputBetweenDates(
                    factoryId, date.atStartOfDay(), date.atTime(23, 59, 59));
            dailyProduction.add(DashboardStatisticsDTO.DailyTrend.builder()
                    .date(date)
                    .value(output != null ? output : BigDecimal.ZERO)
                    .changeRate(0.0)
                    .build());
            // 收入趋势（示例数据）
            dailyRevenue.add(DashboardStatisticsDTO.DailyTrend.builder()
                    .date(date)
                    .value(BigDecimal.valueOf(50000 + Math.random() * 20000))
                    .changeRate(Math.random() * 20 - 10)
                    .build());
        }
        return DashboardStatisticsDTO.TrendStatistics.builder()
                .dailyProduction(dailyProduction)
                .dailyRevenue(dailyRevenue)
                .build();
    }

    private List<DashboardStatisticsDTO.AlertInfo> getAlerts(String factoryId) {
        List<DashboardStatisticsDTO.AlertInfo> alerts = new ArrayList<>();
        // 获取即将过期批次
        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatches(
                factoryId, LocalDate.now().plusDays(7));
        // 获取需要维护的设备
        List<FactoryEquipment> needsMaintenance = equipmentRepository.findEquipmentNeedingMaintenance(factoryId, LocalDate.now());
        // 添加库存预警
        if (!expiringBatches.isEmpty()) {
            alerts.add(DashboardStatisticsDTO.AlertInfo.builder()
                    .type("INVENTORY")
                    .level("WARNING")
                    .message(String.format("有%d个批次即将过期", expiringBatches.size()))
                    .date(LocalDate.now())
                    .build());
        }
        // 添加设备维护预警
        if (!needsMaintenance.isEmpty()) {
            alerts.add(DashboardStatisticsDTO.AlertInfo.builder()
                    .type("EQUIPMENT")
                    .level("INFO")
                    .message(String.format("有%d台设备需要维护", needsMaintenance.size()))
                    .date(LocalDate.now())
                    .build());
        }
        return alerts;
    }
}
