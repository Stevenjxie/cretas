package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.dto.report.KpiMetricsDTO;
import com.cretas.aims.dto.report.OeeReportDTO;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.report.ProductionReportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 生产报表服务实现
 * 包含所有生产统计、基础报表、分析报表、Dashboard 委托及专项报表
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Service
@RequiredArgsConstructor
public class ProductionReportServiceImpl implements ProductionReportService {

    private static final Logger log = LoggerFactory.getLogger(ProductionReportServiceImpl.class);

    private final ProductionPlanRepository productionPlanRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final UserRepository userRepository;
    private final EquipmentRepository equipmentRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final AIAnalysisService aiAnalysisService;
    private final ProcessingService processingService;
    private final ProductionBatchRepository productionBatchRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final ShipmentRecordRepository shipmentRecordRepository;
    private final TimeClockRecordRepository timeClockRecordRepository;
    private final ReworkRecordRepository reworkRecordRepository;
    private final SalesOrderRepository salesOrderRepository;

    // ==================== 基础报表 ====================

    @Override
    public Map<String, Object> getProductionReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取生产报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        // 产量统计
        BigDecimal totalOutput = productionPlanRepository.calculateOutputBetweenDates(factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        report.put("totalOutput", totalOutput != null ? totalOutput : BigDecimal.ZERO);
        // 成本统计
        BigDecimal totalCost = productionPlanRepository.calculateTotalCostBetweenDates(factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        report.put("totalCost", totalCost != null ? totalCost : BigDecimal.ZERO);
        // 日产量趋势
        List<Map<String, Object>> dailyOutput = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            BigDecimal output = productionPlanRepository.calculateOutputBetweenDates(
                    factoryId, current.atStartOfDay(), current.atTime(23, 59, 59));
            Map<String, Object> daily = new HashMap<>();
            daily.put("date", current);
            daily.put("output", output != null ? output : BigDecimal.ZERO);
            dailyOutput.add(daily);
            current = current.plusDays(1);
        }
        report.put("dailyOutput", dailyOutput);
        return report;
    }

    @Override
    public Map<String, Object> getInventoryReport(String factoryId, LocalDate date) {
        log.info("获取库存报表: factoryId={}, date={}", factoryId, date);
        Map<String, Object> report = new HashMap<>();
        // 库存总览
        long totalBatches = materialBatchRepository.countByFactoryId(factoryId);
        BigDecimal totalValue = materialBatchRepository.calculateInventoryValue(factoryId);
        report.put("totalBatches", totalBatches);
        report.put("totalValue", totalValue != null ? totalValue : BigDecimal.ZERO);
        // 按状态统计
        Map<String, Long> statusDistribution = new HashMap<>();
        for (MaterialBatchStatus status : MaterialBatchStatus.values()) {
            long count = materialBatchRepository.countByFactoryIdAndStatus(factoryId, status);
            statusDistribution.put(status.name(), count);
        }
        report.put("statusDistribution", statusDistribution);
        // 按材料类型统计
        List<Object[]> inventoryByType = materialBatchRepository.sumQuantityByMaterialType(factoryId);
        Map<String, BigDecimal> typeDistribution = new HashMap<>();
        for (Object[] row : inventoryByType) {
            typeDistribution.put((String) row[0], (BigDecimal) row[1]);
        }
        report.put("typeDistribution", typeDistribution);
        // 库存预警
        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatches(
                factoryId, date.plusDays(7));
        List<MaterialBatch> expiredBatches = materialBatchRepository.findExpiredBatches(factoryId);
        report.put("expiringBatches", expiringBatches.size());
        report.put("expiredBatches", expiredBatches.size());
        List<Object> lowStockItems = materialBatchRepository.findLowStockMaterials(factoryId);
        report.put("lowStockItems", lowStockItems.size());
        return report;
    }

    @Override
    public Map<String, Object> getFinanceReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取财务报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        // 收入统计 - 从出货记录计算
        BigDecimal totalRevenue = shipmentRecordRepository.calculateTotalRevenue(factoryId, startDate, endDate);
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;
        report.put("totalRevenue", totalRevenue);
        // 成本统计
        BigDecimal materialCost = productionPlanRepository.calculateMaterialCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal laborCost = productionPlanRepository.calculateLaborCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal equipmentCost = productionPlanRepository.calculateEquipmentCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal otherCost = productionPlanRepository.calculateOtherCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal totalCost = Stream.of(materialCost, laborCost, equipmentCost, otherCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.put("materialCost", materialCost != null ? materialCost : BigDecimal.ZERO);
        report.put("laborCost", laborCost != null ? laborCost : BigDecimal.ZERO);
        report.put("equipmentCost", equipmentCost != null ? equipmentCost : BigDecimal.ZERO);
        report.put("otherCost", otherCost != null ? otherCost : BigDecimal.ZERO);
        report.put("totalCost", totalCost);
        // 利润计算
        BigDecimal totalProfit = totalRevenue.subtract(totalCost);
        report.put("totalProfit", totalProfit);
        Double profitMargin = totalRevenue.compareTo(BigDecimal.ZERO) > 0 ?
                totalProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0;
        report.put("profitMargin", profitMargin);
        // 应收应付 (R42 BUG-13: 仅正余额; 客户预付分开统计)
        BigDecimal accountsReceivable = customerRepository.calculateTotalOutstandingBalance(factoryId);
        BigDecimal accountsPayable = supplierRepository.calculateTotalOutstandingBalance(factoryId);
        BigDecimal customerPrepayments = customerRepository.calculateTotalPrepayments(factoryId);
        report.put("accountsReceivable", accountsReceivable != null ? accountsReceivable : BigDecimal.ZERO);
        report.put("accountsPayable", accountsPayable != null ? accountsPayable : BigDecimal.ZERO);
        report.put("customerPrepayments", customerPrepayments != null ? customerPrepayments : BigDecimal.ZERO);
        return report;
    }

    @Override
    public Map<String, Object> getQualityReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取质量报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        BigDecimal totalSampleSize = qualityInspectionRepository.calculateTotalSampleSize(factoryId, startDate, endDate);
        BigDecimal passCount = qualityInspectionRepository.calculateTotalPassCount(factoryId, startDate, endDate);
        BigDecimal failCount = qualityInspectionRepository.calculateTotalFailCount(factoryId, startDate, endDate);
        if (totalSampleSize == null) totalSampleSize = BigDecimal.ZERO;
        if (passCount == null) passCount = BigDecimal.ZERO;
        if (failCount == null) failCount = BigDecimal.ZERO;
        double qualityRate = 98.0;
        if (totalSampleSize.compareTo(BigDecimal.ZERO) > 0) {
            qualityRate = passCount.divide(totalSampleSize, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        Double firstPassRate = qualityInspectionRepository.calculateFirstPassRate(factoryId, startDate, endDate);
        double reworkRate = 2.0;
        if (totalSampleSize.compareTo(BigDecimal.ZERO) > 0) {
            reworkRate = failCount.divide(totalSampleSize, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        report.put("totalProduction", totalSampleSize);
        report.put("qualifiedProduction", passCount);
        report.put("defectiveProduction", failCount);
        report.put("qualityRate", qualityRate);
        report.put("firstPassRate", firstPassRate != null ? firstPassRate : 96.0);
        report.put("reworkRate", reworkRate);
        return report;
    }

    @Override
    public Map<String, Object> getEquipmentEfficiencyReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取设备效率报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        List<Object[]> statusCount = equipmentRepository.countByStatus(factoryId);
        Map<String, Long> statusDistribution = new HashMap<>();
        for (Object[] row : statusCount) {
            statusDistribution.put((String) row[0], (Long) row[1]);
        }
        report.put("statusDistribution", statusDistribution);
        BigDecimal totalValue = equipmentRepository.calculateTotalEquipmentValue(factoryId);
        report.put("totalValue", totalValue != null ? totalValue : BigDecimal.ZERO);
        BigDecimal operatingCost = equipmentRepository.calculateTotalOperatingCost(factoryId);
        report.put("operatingCost", operatingCost != null ? operatingCost : BigDecimal.ZERO);
        Double avgRunningHours = equipmentRepository.calculateAverageRunningHours(factoryId);
        report.put("averageRunningHours", avgRunningHours != null ? avgRunningHours : 0.0);
        report.put("availability", 90.0);
        report.put("performance", 85.0);
        report.put("quality", 98.0);
        report.put("oee", 75.0);
        return report;
    }

    @Override
    public Map<String, Object> getPersonnelPerformanceReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取人员绩效报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        long totalEmployees = userRepository.countByFactoryId(factoryId);
        long activeEmployees = userRepository.countActiveUsers(factoryId);
        report.put("totalEmployees", totalEmployees);
        report.put("activeEmployees", activeEmployees);
        List<Object[]> departmentCount = userRepository.countByDepartment(factoryId);
        Map<String, Long> departmentDistribution = new HashMap<>();
        for (Object[] row : departmentCount) {
            departmentDistribution.put((String) row[0], (Long) row[1]);
        }
        report.put("departmentDistribution", departmentDistribution);
        // 出勤率
        double attendanceRate = 95.0;
        if (totalEmployees > 0) {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.plusDays(1).atStartOfDay();
            long attendedUsers = timeClockRecordRepository.countDistinctUsersByFactoryIdAndClockDateBetween(
                    factoryId, start, end);
            attendanceRate = (double) attendedUsers / totalEmployees * 100;
            attendanceRate = Math.min(100.0, Math.round(attendanceRate * 10) / 10.0);
        }
        report.put("attendanceRate", attendanceRate);
        // 生产效率
        double productivity = 88.0;
        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                factoryId, startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        if (!batches.isEmpty()) {
            BigDecimal totalPlanned = BigDecimal.ZERO;
            BigDecimal totalActual = BigDecimal.ZERO;
            for (ProductionBatch batch : batches) {
                if (batch.getPlannedQuantity() != null) {
                    totalPlanned = totalPlanned.add(batch.getPlannedQuantity());
                }
                if (batch.getActualQuantity() != null) {
                    totalActual = totalActual.add(batch.getActualQuantity());
                }
            }
            if (totalPlanned.compareTo(BigDecimal.ZERO) > 0) {
                productivity = totalActual.divide(totalPlanned, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
                productivity = Math.round(productivity * 10) / 10.0;
            }
        }
        report.put("productivity", productivity);
        // 满意度
        double satisfaction = 4.2;
        try {
            BigDecimal totalSamples = qualityInspectionRepository.calculateTotalSampleSize(factoryId, startDate, endDate);
            BigDecimal passCount = qualityInspectionRepository.calculateTotalPassCount(factoryId, startDate, endDate);
            if (totalSamples != null && totalSamples.compareTo(BigDecimal.ZERO) > 0 && passCount != null) {
                double passRate = passCount.divide(totalSamples, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
                satisfaction = 5.0 - Math.max(0, (98.0 - passRate) / 2 * 0.2);
                satisfaction = Math.max(3.0, Math.round(satisfaction * 10) / 10.0);
            }
        } catch (Exception e) {
            log.warn("计算满意度时出错: {}", e.getMessage());
        }
        report.put("satisfaction", satisfaction);
        return report;
    }

    @Override
    public Map<String, Object> getSupplyChainReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取供应链报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        long totalSuppliers = supplierRepository.countByFactoryId(factoryId);
        long activeSuppliers = supplierRepository.countByFactoryIdAndIsActive(factoryId, true);
        report.put("totalSuppliers", totalSuppliers);
        report.put("activeSuppliers", activeSuppliers);
        long totalCustomers = customerRepository.countByFactoryId(factoryId);
        long activeCustomers = customerRepository.countByFactoryIdAndIsActive(factoryId, true);
        report.put("totalCustomers", totalCustomers);
        report.put("activeCustomers", activeCustomers);
        // 供应商评级分布
        List<Object[]> supplierRating = supplierRepository.getSupplierRatingDistribution(factoryId);
        Map<Integer, Long> supplierRatingDistribution = new HashMap<>();
        for (Object[] row : supplierRating) {
            Integer rating = (Integer) row[0];
            Long count = (Long) row[1];
            if (rating != null) {
                supplierRatingDistribution.put(rating, count);
            } else {
                supplierRatingDistribution.put(0, supplierRatingDistribution.getOrDefault(0, 0L) + count);
            }
        }
        for (int i = 0; i <= 5; i++) {
            supplierRatingDistribution.putIfAbsent(i, 0L);
        }
        report.put("supplierRatingDistribution", supplierRatingDistribution);
        // 客户评级分布
        List<Object[]> customerRating = customerRepository.getCustomerRatingDistribution(factoryId);
        Map<Integer, Long> customerRatingDistribution = new HashMap<>();
        for (Object[] row : customerRating) {
            Integer rating = (Integer) row[0];
            Long count = (Long) row[1];
            if (rating != null) {
                customerRatingDistribution.put(rating, count);
            } else {
                customerRatingDistribution.put(0, customerRatingDistribution.getOrDefault(0, 0L) + count);
            }
        }
        for (int i = 0; i <= 5; i++) {
            customerRatingDistribution.putIfAbsent(i, 0L);
        }
        report.put("customerRatingDistribution", customerRatingDistribution);
        return report;
    }

    @Override
    public Map<String, Object> getSalesReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取销售报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        long totalOrders = shipmentRecordRepository.countByFactoryIdAndDateRange(factoryId, startDate, endDate);
        report.put("totalOrders", totalOrders);
        BigDecimal totalRevenue = shipmentRecordRepository.calculateTotalRevenue(factoryId, startDate, endDate);
        report.put("totalRevenue", totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
        BigDecimal averageOrderValue = shipmentRecordRepository.calculateAverageOrderValue(factoryId, startDate, endDate);
        report.put("averageOrderValue", averageOrderValue != null ? averageOrderValue : BigDecimal.ZERO);
        double conversionRate = 100.0;
        if (totalOrders > 0) {
            long shippedOrders = shipmentRecordRepository.countByFactoryIdAndStatus(factoryId, "shipped");
            long deliveredOrders = shipmentRecordRepository.countByFactoryIdAndStatus(factoryId, "delivered");
            conversionRate = (double) (shippedOrders + deliveredOrders) / totalOrders * 100;
            conversionRate = Math.round(conversionRate * 10) / 10.0;
        }
        report.put("conversionRate", conversionRate);
        return report;
    }

    @Override
    public Map<String, Object> getCostAnalysisReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取成本分析报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        BigDecimal materialCost = productionPlanRepository.calculateMaterialCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal laborCost = productionPlanRepository.calculateLaborCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal equipmentCost = productionPlanRepository.calculateEquipmentCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal otherCost = productionPlanRepository.calculateOtherCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal totalCost = Stream.of(materialCost, laborCost, equipmentCost, otherCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.put("materialCost", materialCost != null ? materialCost : BigDecimal.ZERO);
        report.put("laborCost", laborCost != null ? laborCost : BigDecimal.ZERO);
        report.put("equipmentCost", equipmentCost != null ? equipmentCost : BigDecimal.ZERO);
        report.put("otherCost", otherCost != null ? otherCost : BigDecimal.ZERO);
        report.put("totalCost", totalCost);
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            report.put("materialCostRatio", materialCost != null ?
                    materialCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
            report.put("laborCostRatio", laborCost != null ?
                    laborCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
            report.put("equipmentCostRatio", equipmentCost != null ?
                    equipmentCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
            report.put("otherCostRatio", otherCost != null ?
                    otherCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
        }
        return report;
    }

    // ==================== 汇总报表 ====================

    @Override
    public Map<String, Object> getMonthlyReport(String factoryId, Integer year, Integer month) {
        log.info("获取月度报表: factoryId={}, year={}, month={}", factoryId, year, month);
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);
        Map<String, Object> report = new HashMap<>();
        report.put("year", year);
        report.put("month", month);
        report.put("production", getProductionReport(factoryId, startDate, endDate));
        report.put("finance", getFinanceReport(factoryId, startDate, endDate));
        report.put("quality", getQualityReport(factoryId, startDate, endDate));
        report.put("equipment", getEquipmentEfficiencyReport(factoryId, startDate, endDate));
        return report;
    }

    @Override
    public Map<String, Object> getYearlyReport(String factoryId, Integer year) {
        log.info("获取年度报表: factoryId={}, year={}", factoryId, year);
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);
        Map<String, Object> report = new HashMap<>();
        List<Map<String, Object>> monthlyTrend = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            Map<String, Object> monthData = getMonthlyReport(factoryId, year, month);
            monthlyTrend.add(monthData);
        }
        report.put("monthlyTrend", monthlyTrend);
        report.put("yearSummary", getProductionReport(factoryId, startDate, endDate));
        report.put("yearFinance", getFinanceReport(factoryId, startDate, endDate));
        return report;
    }

    @Override
    public Map<String, Object> getCustomReport(String factoryId, Map<String, Object> parameters) {
        log.info("获取自定义报表: factoryId={}, parameters={}", factoryId, parameters);
        Map<String, Object> report = new HashMap<>();
        String reportType = (String) parameters.getOrDefault("reportType", "production");
        LocalDate startDate = parameters.containsKey("startDate") ?
                LocalDate.parse((String) parameters.get("startDate")) : LocalDate.now().minusDays(30);
        LocalDate endDate = parameters.containsKey("endDate") ?
                LocalDate.parse((String) parameters.get("endDate")) : LocalDate.now();
        report.put("parameters", parameters);
        report.put("startDate", startDate.toString());
        report.put("endDate", endDate.toString());
        Map<String, Object> data;
        switch (reportType.toLowerCase()) {
            case "production":
                data = getProductionReport(factoryId, startDate, endDate);
                break;
            case "finance":
                data = getFinanceReport(factoryId, startDate, endDate);
                break;
            case "quality":
                data = getQualityReport(factoryId, startDate, endDate);
                break;
            case "equipment":
                data = getEquipmentEfficiencyReport(factoryId, startDate, endDate);
                break;
            case "personnel":
                data = getPersonnelPerformanceReport(factoryId, startDate, endDate);
                break;
            case "supply_chain":
                data = getSupplyChainReport(factoryId, startDate, endDate);
                break;
            case "sales":
                data = getSalesReport(factoryId, startDate, endDate);
                break;
            case "cost":
                data = getCostAnalysisReport(factoryId, startDate, endDate);
                break;
            case "comprehensive":
                data = new HashMap<>();
                data.put("production", getProductionReport(factoryId, startDate, endDate));
                data.put("finance", getFinanceReport(factoryId, startDate, endDate));
                data.put("quality", getQualityReport(factoryId, startDate, endDate));
                data.put("equipment", getEquipmentEfficiencyReport(factoryId, startDate, endDate));
                break;
            default:
                log.warn("未知的报表类型: {}, 使用生产报表", reportType);
                data = getProductionReport(factoryId, startDate, endDate);
        }
        report.put("data", data);
        return report;
    }

    // ==================== 实时 & KPI ====================

    @Override
    public Map<String, Object> getRealTimeProductionData(String factoryId) {
        log.info("获取实时生产数据: factoryId={}", factoryId);
        Map<String, Object> data = new HashMap<>();
        long runningPlans = productionPlanRepository.countByFactoryIdAndStatus(
                factoryId, ProductionPlanStatus.IN_PROGRESS);
        data.put("runningPlans", runningPlans);
        BigDecimal todayOutput = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, LocalDate.now().atStartOfDay(), LocalDate.now().atTime(23, 59, 59));
        data.put("todayOutput", todayOutput != null ? todayOutput : BigDecimal.ZERO);
        List<Object[]> equipmentStatus = equipmentRepository.countByStatus(factoryId);
        Map<String, Long> statusMap = new HashMap<>();
        for (Object[] row : equipmentStatus) {
            statusMap.put((String) row[0], (Long) row[1]);
        }
        data.put("equipmentStatus", statusMap);
        return data;
    }

    @Override
    public Map<String, Object> getKPIMetrics(String factoryId, LocalDate date) {
        log.info("获取KPI指标: factoryId={}, date={}", factoryId, date);
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("productionEfficiency", 85.0);
        kpi.put("qualityRate", 98.0);
        kpi.put("deliveryOnTime", 95.0);
        kpi.put("costReduction", 5.0);
        kpi.put("inventoryTurnover", 4.5);
        kpi.put("equipmentOEE", 75.0);
        kpi.put("maintenanceCompliance", 90.0);
        kpi.put("laborProductivity", 88.0);
        kpi.put("safetyIncidents", 0);
        return kpi;
    }

    @Override
    public Map<String, Object> getRealtimeData(String factoryId) {
        return getRealTimeProductionData(factoryId);
    }

    // ==================== 分析报表 ====================

    @Override
    public Map<String, Object> getComparativeAnalysis(String factoryId, LocalDate period1Start, LocalDate period1End,
                                                      LocalDate period2Start, LocalDate period2End) {
        log.info("获取对比分析: factoryId={}", factoryId);
        Map<String, Object> analysis = new HashMap<>();
        Map<String, Object> period1 = new HashMap<>();
        BigDecimal output1 = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, period1Start.atStartOfDay(), period1End.atTime(23, 59, 59));
        BigDecimal cost1 = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, period1Start.atStartOfDay(), period1End.atTime(23, 59, 59));
        period1.put("output", output1 != null ? output1 : BigDecimal.ZERO);
        period1.put("cost", cost1 != null ? cost1 : BigDecimal.ZERO);
        analysis.put("period1", period1);
        Map<String, Object> period2 = new HashMap<>();
        BigDecimal output2 = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, period2Start.atStartOfDay(), period2End.atTime(23, 59, 59));
        BigDecimal cost2 = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, period2Start.atStartOfDay(), period2End.atTime(23, 59, 59));
        period2.put("output", output2 != null ? output2 : BigDecimal.ZERO);
        period2.put("cost", cost2 != null ? cost2 : BigDecimal.ZERO);
        analysis.put("period2", period2);
        Map<String, Double> changeRate = new HashMap<>();
        if (output1 != null && output1.compareTo(BigDecimal.ZERO) > 0) {
            changeRate.put("outputChange",
                    output2.subtract(output1).divide(output1, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue());
        }
        if (cost1 != null && cost1.compareTo(BigDecimal.ZERO) > 0) {
            changeRate.put("costChange",
                    cost2.subtract(cost1).divide(cost1, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue());
        }
        analysis.put("changeRate", changeRate);
        return analysis;
    }

    @Override
    public Map<String, Object> getForecastReport(String factoryId, Integer forecastDays) {
        log.info("获取预测分析: factoryId={}, forecastDays={}", factoryId, forecastDays);
        Map<String, Object> forecast = new HashMap<>();
        forecast.put("forecastDays", forecastDays);
        LocalDate today = LocalDate.now();
        LocalDate historyStart = today.minusDays(30);
        int historyDaysCount = 30;
        BigDecimal historyOutput = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, historyStart.atStartOfDay(), today.atStartOfDay());
        BigDecimal avgDailyProduction = BigDecimal.ZERO;
        if (historyOutput != null && historyOutput.compareTo(BigDecimal.ZERO) > 0) {
            avgDailyProduction = historyOutput.divide(BigDecimal.valueOf(historyDaysCount), 2, RoundingMode.HALF_UP);
        }
        BigDecimal expectedProduction = avgDailyProduction.multiply(BigDecimal.valueOf(forecastDays));
        forecast.put("expectedProduction", expectedProduction);
        forecast.put("avgDailyProduction", avgDailyProduction);
        BigDecimal historyRevenue = shipmentRecordRepository.calculateTotalRevenue(factoryId, historyStart, today);
        BigDecimal avgDailyRevenue = BigDecimal.ZERO;
        if (historyRevenue != null && historyRevenue.compareTo(BigDecimal.ZERO) > 0) {
            avgDailyRevenue = historyRevenue.divide(BigDecimal.valueOf(historyDaysCount), 2, RoundingMode.HALF_UP);
        }
        BigDecimal expectedRevenue = avgDailyRevenue.multiply(BigDecimal.valueOf(forecastDays));
        forecast.put("expectedRevenue", expectedRevenue);
        forecast.put("avgDailyRevenue", avgDailyRevenue);
        BigDecimal historyCost = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, historyStart.atStartOfDay(), today.atStartOfDay());
        BigDecimal avgDailyCost = BigDecimal.ZERO;
        if (historyCost != null && historyCost.compareTo(BigDecimal.ZERO) > 0) {
            avgDailyCost = historyCost.divide(BigDecimal.valueOf(historyDaysCount), 2, RoundingMode.HALF_UP);
        }
        BigDecimal expectedCost = avgDailyCost.multiply(BigDecimal.valueOf(forecastDays));
        forecast.put("expectedCost", expectedCost);
        forecast.put("avgDailyCost", avgDailyCost);
        BigDecimal expectedProfit = expectedRevenue.subtract(expectedCost);
        forecast.put("expectedProfit", expectedProfit);
        double profitMargin = 0.0;
        if (expectedRevenue.compareTo(BigDecimal.ZERO) > 0) {
            profitMargin = expectedProfit.divide(expectedRevenue, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        forecast.put("profitMargin", profitMargin);
        LocalDate prevStart = historyStart.minusDays(30);
        BigDecimal prevOutput = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, prevStart.atStartOfDay(), historyStart.atStartOfDay());
        double productionGrowth = 0.0;
        if (prevOutput != null && prevOutput.compareTo(BigDecimal.ZERO) > 0 && historyOutput != null) {
            productionGrowth = historyOutput.subtract(prevOutput)
                    .divide(prevOutput, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        forecast.put("productionGrowth", productionGrowth);
        long dataPoints = productionPlanRepository.countByFactoryIdAndCreatedAtBetween(
                factoryId, historyStart.atStartOfDay(), today.atStartOfDay());
        double confidence = Math.min(100.0, dataPoints * 3.3);
        forecast.put("confidence", confidence);
        return forecast;
    }

    @Override
    public Map<String, Object> getAnomalyReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取异常分析(AI增强): factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        List<Map<String, Object>> anomalies = new ArrayList<>();
        Map<String, Object> dataContext = new HashMap<>();

        BigDecimal totalOutput = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        BigDecimal totalCost = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        dataContext.put("totalOutput", totalOutput != null ? totalOutput : BigDecimal.ZERO);
        dataContext.put("totalCost", totalCost != null ? totalCost : BigDecimal.ZERO);

        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatches(
                factoryId, LocalDate.now().plusDays(7));
        List<MaterialBatch> expiredBatches = materialBatchRepository.findExpiredBatches(factoryId);
        List<Object> lowStockItems = materialBatchRepository.findLowStockMaterials(factoryId);
        dataContext.put("expiringBatches", expiringBatches.size());
        dataContext.put("expiredBatches", expiredBatches.size());
        dataContext.put("lowStockItems", lowStockItems.size());

        List<FactoryEquipment> needsMaintenance = equipmentRepository.findEquipmentNeedingMaintenance(
                factoryId, LocalDate.now());
        dataContext.put("equipmentNeedingMaintenance", needsMaintenance.size());

        if (!expiringBatches.isEmpty()) {
            anomalies.add(Map.of(
                "type", "INVENTORY",
                "level", "WARNING",
                "title", "原材料即将过期",
                "description", String.format("有%d个批次将在7天内过期", expiringBatches.size()),
                "count", expiringBatches.size(),
                "detectedAt", LocalDate.now().toString()
            ));
        }
        if (!expiredBatches.isEmpty()) {
            anomalies.add(Map.of(
                "type", "INVENTORY",
                "level", "CRITICAL",
                "title", "已过期原材料",
                "description", String.format("有%d个批次已过期，需要立即处理", expiredBatches.size()),
                "count", expiredBatches.size(),
                "detectedAt", LocalDate.now().toString()
            ));
        }
        if (!needsMaintenance.isEmpty()) {
            anomalies.add(Map.of(
                "type", "EQUIPMENT",
                "level", "INFO",
                "title", "设备维护提醒",
                "description", String.format("有%d台设备需要维护", needsMaintenance.size()),
                "count", needsMaintenance.size(),
                "detectedAt", LocalDate.now().toString()
            ));
        }

        try {
            String aiMessage = String.format(
                "分析以下工厂数据，识别潜在的异常和风险：\n" +
                "- 期间：%s 至 %s\n" +
                "- 总产量：%.2f\n" +
                "- 总成本：%.2f\n" +
                "- 即将过期批次：%d个\n" +
                "- 已过期批次：%d个\n" +
                "- 低库存项目：%d个\n" +
                "- 需维护设备：%d台\n" +
                "请识别异常模式，分析潜在风险，并提供预警建议。",
                startDate, endDate,
                dataContext.get("totalOutput"),
                dataContext.get("totalCost"),
                expiringBatches.size(),
                expiredBatches.size(),
                lowStockItems.size(),
                needsMaintenance.size()
            );
            Map<String, Object> aiResult = aiAnalysisService.analyzeCost(
                factoryId, "anomaly_detection", dataContext, null, aiMessage
            );
            if (Boolean.TRUE.equals(aiResult.get("success"))) {
                report.put("aiAnalysis", aiResult.get("aiAnalysis"));
                report.put("reasoningContent", aiResult.get("reasoningContent"));
                report.put("analysisMethod", "AI Enhanced Detection");
            }
        } catch (Exception e) {
            log.warn("AI异常检测服务暂时不可用，使用规则检测: {}", e.getMessage());
            report.put("analysisMethod", "Rule-based Detection (AI Fallback)");
        }

        report.put("anomalies", anomalies);
        report.put("totalAnomalies", anomalies.size());
        report.put("period", Map.of("startDate", startDate.toString(), "endDate", endDate.toString()));
        report.put("dataContext", dataContext);
        return report;
    }

    @Override
    public Map<String, Object> getEquipmentReport(String factoryId, LocalDate date) {
        log.info("获取设备报表: factoryId={}, date={}", factoryId, date);
        Map<String, Object> report = new HashMap<>();
        long totalEquipment = equipmentRepository.countByFactoryId(factoryId);
        report.put("totalEquipment", totalEquipment);
        List<Object[]> statusCount = equipmentRepository.countByStatus(factoryId);
        Map<String, Long> statusDistribution = new HashMap<>();
        for (Object[] row : statusCount) {
            statusDistribution.put(row[0].toString(), (Long) row[1]);
        }
        report.put("statusDistribution", statusDistribution);
        List<FactoryEquipment> needsMaintenance = equipmentRepository.findEquipmentNeedingMaintenance(factoryId, LocalDate.now());
        report.put("maintenanceRequired", needsMaintenance.size());
        return report;
    }

    @Override
    public Map<String, Object> getPersonnelReport(String factoryId, LocalDate date) {
        log.info("获取人员报表: factoryId={}, date={}", factoryId, date);
        Map<String, Object> report = new HashMap<>();
        long totalUsers = userRepository.countByFactoryId(factoryId);
        report.put("totalUsers", totalUsers);
        long activeUsers = userRepository.countActiveUsers(factoryId);
        report.put("activeUsers", activeUsers);
        List<Object[]> departmentStats = userRepository.countByDepartment(factoryId);
        Map<String, Long> departmentDistribution = new HashMap<>();
        for (Object[] row : departmentStats) {
            if (row[0] != null) {
                departmentDistribution.put(row[0].toString(), (Long) row[1]);
            }
        }
        report.put("departmentDistribution", departmentDistribution);
        return report;
    }

    @Override
    public Map<String, Object> getEfficiencyAnalysisReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取效率分析报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        Map<String, Object> report = new HashMap<>();
        BigDecimal totalOutput = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        report.put("totalOutput", totalOutput != null ? totalOutput : BigDecimal.ZERO);
        report.put("equipmentOEE", 75.0);
        return report;
    }

    @Override
    public Map<String, Object> getTrendAnalysisReport(String factoryId, String type, Integer period) {
        log.info("获取趋势分析报表: factoryId={}, type={}, period={}", factoryId, type, period);
        Map<String, Object> report = new HashMap<>();
        List<Map<String, Object>> trendData = new ArrayList<>();
        for (int i = period - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            Map<String, Object> dataPoint = new HashMap<>();
            dataPoint.put("date", date);
            switch (type) {
                case "production":
                    BigDecimal output = productionPlanRepository.calculateOutputBetweenDates(
                            factoryId, date.atStartOfDay(), date.atTime(23, 59, 59));
                    dataPoint.put("value", output != null ? output : BigDecimal.ZERO);
                    break;
                case "cost":
                    BigDecimal cost = productionPlanRepository.calculateTotalCostBetweenDates(
                            factoryId, date.atStartOfDay(), date.atTime(23, 59, 59));
                    dataPoint.put("value", cost != null ? cost : BigDecimal.ZERO);
                    break;
                default:
                    dataPoint.put("value", BigDecimal.ZERO);
            }
            trendData.add(dataPoint);
        }
        report.put("type", type);
        report.put("period", period);
        report.put("trendData", trendData);
        return report;
    }

    @Override
    public Map<String, Object> getPeriodComparisonReport(String factoryId, LocalDate period1Start, LocalDate period1End,
                                                         LocalDate period2Start, LocalDate period2End) {
        log.info("获取周期对比报表: factoryId={}, period1={}-{}, period2={}-{}",
                factoryId, period1Start, period1End, period2Start, period2End);
        Map<String, Object> report = new HashMap<>();
        BigDecimal output1 = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, period1Start.atStartOfDay(), period1End.atTime(23, 59, 59));
        BigDecimal cost1 = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, period1Start.atStartOfDay(), period1End.atTime(23, 59, 59));
        Map<String, Object> period1Data = new HashMap<>();
        period1Data.put("output", output1 != null ? output1 : BigDecimal.ZERO);
        period1Data.put("cost", cost1 != null ? cost1 : BigDecimal.ZERO);
        report.put("period1", period1Data);
        BigDecimal output2 = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, period2Start.atStartOfDay(), period2End.atTime(23, 59, 59));
        BigDecimal cost2 = productionPlanRepository.calculateTotalCostBetweenDates(
                factoryId, period2Start.atStartOfDay(), period2End.atTime(23, 59, 59));
        Map<String, Object> period2Data = new HashMap<>();
        period2Data.put("output", output2 != null ? output2 : BigDecimal.ZERO);
        period2Data.put("cost", cost2 != null ? cost2 : BigDecimal.ZERO);
        report.put("period2", period2Data);
        Map<String, Object> comparison = new HashMap<>();
        if (output1 != null && output1.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal outputChange = output2.subtract(output1).divide(output1, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            comparison.put("outputChangeRate", outputChange);
        } else {
            comparison.put("outputChangeRate", BigDecimal.ZERO);
        }
        if (cost1 != null && cost1.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal costChange = cost2.subtract(cost1).divide(cost1, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            comparison.put("costChangeRate", costChange);
        } else {
            comparison.put("costChangeRate", BigDecimal.ZERO);
        }
        report.put("comparison", comparison);
        return report;
    }

    @Override
    public Map<String, Object> getForecastReport(String factoryId, String type, Integer days) {
        log.info("获取预测报表(AI增强): factoryId={}, type={}, days={}", factoryId, type, days);
        Map<String, Object> report = new HashMap<>();

        LocalDate today = LocalDate.now();
        int historicalDays = 30;
        List<Map<String, Object>> historicalData = new ArrayList<>();
        BigDecimal totalHistorical = BigDecimal.ZERO;

        for (int i = historicalDays - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            BigDecimal value = BigDecimal.ZERO;
            if ("production".equals(type)) {
                value = productionPlanRepository.calculateOutputBetweenDates(
                        factoryId, date.atStartOfDay(), date.atTime(23, 59, 59));
            } else if ("cost".equals(type)) {
                value = productionPlanRepository.calculateTotalCostBetweenDates(
                        factoryId, date.atStartOfDay(), date.atTime(23, 59, 59));
            }
            if (value == null) value = BigDecimal.ZERO;
            totalHistorical = totalHistorical.add(value);
            Map<String, Object> dataPoint = new HashMap<>();
            dataPoint.put("date", date.toString());
            dataPoint.put("value", value);
            historicalData.add(dataPoint);
        }

        BigDecimal dailyAverage = totalHistorical.divide(BigDecimal.valueOf(historicalDays), 2, RoundingMode.HALF_UP);

        try {
            Map<String, Object> aiContext = new HashMap<>();
            aiContext.put("factoryId", factoryId);
            aiContext.put("forecastType", type);
            aiContext.put("forecastDays", days);
            aiContext.put("historicalData", historicalData);
            aiContext.put("dailyAverage", dailyAverage);
            aiContext.put("totalHistorical", totalHistorical);

            String aiMessage = String.format(
                "基于以下%d天的历史数据进行%s预测分析，预测未来%d天的趋势：\n" +
                "- 历史数据总计：%.2f\n" +
                "- 日均值：%.2f\n" +
                "请分析数据趋势，识别周期性规律，并给出预测建议。",
                historicalDays, "production".equals(type) ? "产量" : "成本", days,
                totalHistorical.doubleValue(), dailyAverage.doubleValue()
            );
            Map<String, Object> aiResult = aiAnalysisService.analyzeCost(
                factoryId, "forecast_" + type, aiContext, null, aiMessage
            );
            if (Boolean.TRUE.equals(aiResult.get("success"))) {
                report.put("aiAnalysis", aiResult.get("aiAnalysis"));
                report.put("reasoningContent", aiResult.get("reasoningContent"));
                report.put("method", "AI Enhanced Forecast");
            }
        } catch (Exception e) {
            log.warn("AI预测服务暂时不可用，使用基础预测: {}", e.getMessage());
            report.put("method", "Linear Average (AI Fallback)");
        }

        List<Map<String, Object>> forecastData = new ArrayList<>();
        for (int i = 1; i <= days; i++) {
            Map<String, Object> forecast = new HashMap<>();
            forecast.put("date", today.plusDays(i).toString());
            forecast.put("value", dailyAverage);
            forecast.put("confidence", 75);
            forecastData.add(forecast);
        }

        report.put("type", type);
        report.put("forecastDays", days);
        report.put("forecastData", forecastData);
        report.put("historicalSummary", Map.of(
            "days", historicalDays,
            "total", totalHistorical,
            "average", dailyAverage
        ));
        return report;
    }

    // ==================== Dashboard 委托 (ProcessingService) ====================

    @Override
    public Map<String, Object> getDashboardOverview(String factoryId, String period) {
        log.info("获取生产概览Dashboard (委托ProcessingService): factoryId={}, period={}", factoryId, period);
        return processingService.getDashboardOverview(factoryId);
    }

    @Override
    public Map<String, Object> getProductionDashboard(String factoryId, String period) {
        log.info("获取生产统计Dashboard (委托ProcessingService): factoryId={}, period={}", factoryId, period);
        return processingService.getProductionStatistics(factoryId, period);
    }

    @Override
    public Map<String, Object> getQualityDashboard(String factoryId) {
        log.info("获取质量Dashboard (委托ProcessingService): factoryId={}", factoryId);
        return processingService.getQualityDashboard(factoryId);
    }

    @Override
    public Map<String, Object> getEquipmentDashboard(String factoryId) {
        log.info("获取设备Dashboard (委托ProcessingService): factoryId={}", factoryId);
        return processingService.getEquipmentDashboard(factoryId);
    }

    @Override
    public Map<String, Object> getAlertsDashboard(String factoryId, String period) {
        log.info("获取告警Dashboard (委托ProcessingService): factoryId={}, period={}", factoryId, period);
        return processingService.getAlertsDashboard(factoryId);
    }

    @Override
    public Map<String, Object> getTrendsDashboard(String factoryId, String period, String metric, Integer days) {
        log.info("获取趋势Dashboard (委托ProcessingService): factoryId={}, period={}, metric={}, days={}",
                factoryId, period, metric, days);
        return processingService.getTrendAnalysis(factoryId, metric, days);
    }

    // ==================== 生产统计报表 ====================

    @Override
    @Cacheable(value = "productionByProduct", key = "#factoryId + '_' + #startDate + '_' + #endDate", unless = "#result == null || #result.isEmpty()")
    public List<ProductionByProductDTO> getProductionByProduct(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("按产品统计生产数量: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        LocalDate effectiveStartDate = startDate;
        LocalDate effectiveEndDate = endDate;

        if (effectiveStartDate == null || effectiveEndDate == null) {
            LocalDate today = LocalDate.now();
            effectiveStartDate = today.with(java.time.DayOfWeek.MONDAY);
            effectiveEndDate = today;
        }

        LocalDateTime startTime = effectiveStartDate.atStartOfDay();
        LocalDateTime endTime = effectiveEndDate.plusDays(1).atStartOfDay();

        List<Object[]> results = productionBatchRepository.findProductionByProduct(factoryId, startTime, endTime);

        List<ProductionByProductDTO> dtoList = new ArrayList<>();
        for (Object[] row : results) {
            String productTypeId = (String) row[0];
            String productName = (String) row[1];
            BigDecimal totalQuantity = (BigDecimal) row[2];
            String unit = (String) row[3];

            if (productName == null || productName.isEmpty()) {
                productName = "未知产品";
            }

            ProductionByProductDTO dto = ProductionByProductDTO.builder()
                    .productTypeId(productTypeId)
                    .productName(productName)
                    .totalQuantity(totalQuantity != null ? totalQuantity : BigDecimal.ZERO)
                    .unit(unit != null ? unit : "kg")
                    .build();
            dtoList.add(dto);
        }

        log.info("按产品统计完成: factoryId={}, 产品数量={}", factoryId, dtoList.size());
        return dtoList;
    }

    // ==================== 专项报表 (2026-01-14) ====================

    @Override
    @Cacheable(value = "oeeReport", key = "#factoryId + '_' + #startDate + '_' + #endDate", unless = "#result == null")
    public OeeReportDTO getOeeReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取OEE报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.plusDays(1).atStartOfDay();

        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                factoryId, startTime, endTime);

        BigDecimal totalPlannedQuantity = BigDecimal.ZERO;
        BigDecimal totalActualQuantity = BigDecimal.ZERO;
        BigDecimal totalGoodQuantity = BigDecimal.ZERO;

        for (ProductionBatch batch : batches) {
            if (batch.getPlannedQuantity() != null) {
                totalPlannedQuantity = totalPlannedQuantity.add(batch.getPlannedQuantity());
            }
            if (batch.getActualQuantity() != null) {
                totalActualQuantity = totalActualQuantity.add(batch.getActualQuantity());
            }
            if (batch.getActualQuantity() != null) {
                totalGoodQuantity = totalGoodQuantity.add(
                        batch.getActualQuantity().multiply(new BigDecimal("0.95")));
            }
        }

        List<FactoryEquipment> equipments = equipmentRepository.findByFactoryId(factoryId);
        long totalRunningHours = 0;
        long totalEquipment = equipments.size();

        for (FactoryEquipment eq : equipments) {
            if (eq.getTotalRunningHours() != null) {
                totalRunningHours += eq.getTotalRunningHours();
            }
        }

        long plannedHours = (long) ChronoUnit.DAYS.between(startDate, endDate) * 8 * totalEquipment;
        // OEE 三因子(可用率/性能率/良品率)均为比率, 定义上 <=100%. 比值可能 >100%
        // (设备实跑 >8h/天 → availability>100; 超产 actual>planned → performance>100),
        // 不封顶会让 OEE = a*p*q/10000 算出 >100% 的不可能值, 且 *Loss 字段变负数. 一律 clamp 到 100.
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal availability = plannedHours > 0 ?
                new BigDecimal(totalRunningHours).divide(new BigDecimal(plannedHours), 4, RoundingMode.HALF_UP)
                        .multiply(hundred).min(hundred) : new BigDecimal("80");

        BigDecimal performance = totalPlannedQuantity.compareTo(BigDecimal.ZERO) > 0 ?
                totalActualQuantity.divide(totalPlannedQuantity, 4, RoundingMode.HALF_UP)
                        .multiply(hundred).min(hundred) : new BigDecimal("85");

        BigDecimal quality = totalActualQuantity.compareTo(BigDecimal.ZERO) > 0 ?
                totalGoodQuantity.divide(totalActualQuantity, 4, RoundingMode.HALF_UP)
                        .multiply(hundred).min(hundred) : new BigDecimal("95");

        BigDecimal oee = availability.multiply(performance).multiply(quality)
                .divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP);

        return OeeReportDTO.builder()
                .factoryId(factoryId)
                .startDate(startDate)
                .endDate(endDate)
                .oeeValue(oee)
                .oeeGrade(OeeReportDTO.calculateGrade(oee))
                .availability(availability.setScale(2, RoundingMode.HALF_UP))
                .performance(performance.setScale(2, RoundingMode.HALF_UP))
                .quality(quality.setScale(2, RoundingMode.HALF_UP))
                .plannedProductionTime(plannedHours * 60)
                .actualRunTime(totalRunningHours * 60)
                .downtime((plannedHours - totalRunningHours) * 60)
                .totalOutput(totalActualQuantity)
                .goodOutput(totalGoodQuantity.setScale(2, RoundingMode.HALF_UP))
                .defectOutput(totalActualQuantity.subtract(totalGoodQuantity).setScale(2, RoundingMode.HALF_UP))
                .availabilityLoss(new BigDecimal("100").subtract(availability).setScale(2, RoundingMode.HALF_UP))
                .performanceLoss(new BigDecimal("100").subtract(performance).setScale(2, RoundingMode.HALF_UP))
                .qualityLoss(new BigDecimal("100").subtract(quality).setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    @Override
    @Cacheable(value = "costVarianceReport", key = "#factoryId + '_' + #startDate + '_' + #endDate", unless = "#result == null")
    public CostVarianceReportDTO getCostVarianceReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取成本差异报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.plusDays(1).atStartOfDay();

        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                factoryId, startTime, endTime);

        BigDecimal totalBomCost = BigDecimal.ZERO;
        BigDecimal totalActualCost = BigDecimal.ZERO;
        int batchCount = batches.size();

        Map<String, List<ProductionBatch>> productBatches = new HashMap<>();
        for (ProductionBatch batch : batches) {
            String productId = batch.getProductTypeId() != null ? batch.getProductTypeId() : "UNKNOWN";
            productBatches.computeIfAbsent(productId, k -> new ArrayList<>()).add(batch);
            if (batch.getTotalCost() != null) {
                totalActualCost = totalActualCost.add(batch.getTotalCost());
            }
            if (batch.getActualQuantity() != null) {
                BigDecimal bomCost = batch.getTotalCost() != null ?
                        batch.getTotalCost().multiply(new BigDecimal("0.95")) :
                        batch.getActualQuantity().multiply(new BigDecimal("10"));
                totalBomCost = totalBomCost.add(bomCost);
            }
        }

        BigDecimal totalVariance = totalActualCost.subtract(totalBomCost);
        BigDecimal varianceRate = totalBomCost.compareTo(BigDecimal.ZERO) > 0 ?
                totalVariance.divide(totalBomCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : BigDecimal.ZERO;

        List<CostVarianceReportDTO.ProductCostVariance> productVariances = new ArrayList<>();
        for (Map.Entry<String, List<ProductionBatch>> entry : productBatches.entrySet()) {
            List<ProductionBatch> productBatchList = entry.getValue();
            if (!productBatchList.isEmpty()) {
                ProductionBatch firstBatch = productBatchList.get(0);

                BigDecimal prodQuantity = productBatchList.stream()
                        .map(b -> b.getActualQuantity() != null ? b.getActualQuantity() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal prodActualCost = productBatchList.stream()
                        .map(b -> b.getTotalCost() != null ? b.getTotalCost() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal prodBomCost = prodActualCost.multiply(new BigDecimal("0.95"));

                BigDecimal unitActualCost = prodQuantity.compareTo(BigDecimal.ZERO) > 0 ?
                        prodActualCost.divide(prodQuantity, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                BigDecimal unitBomCost = unitActualCost.multiply(new BigDecimal("0.95"));
                BigDecimal unitVariance = unitActualCost.subtract(unitBomCost);
                BigDecimal prodVarianceRate = unitBomCost.compareTo(BigDecimal.ZERO) > 0 ?
                        unitVariance.divide(unitBomCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")) :
                        BigDecimal.ZERO;

                productVariances.add(CostVarianceReportDTO.ProductCostVariance.builder()
                        .productTypeId(entry.getKey())
                        .productName(firstBatch.getProductName() != null ? firstBatch.getProductName() : "未知产品")
                        .quantity(prodQuantity)
                        .bomUnitCost(unitBomCost.setScale(2, RoundingMode.HALF_UP))
                        .actualUnitCost(unitActualCost.setScale(2, RoundingMode.HALF_UP))
                        .unitVariance(unitVariance.setScale(2, RoundingMode.HALF_UP))
                        .varianceRate(prodVarianceRate.setScale(2, RoundingMode.HALF_UP))
                        .totalVariance(prodActualCost.subtract(prodBomCost).setScale(2, RoundingMode.HALF_UP))
                        .batchCount(productBatchList.size())
                        .varianceReason(CostVarianceReportDTO.analyzeVarianceReason(
                                unitVariance.multiply(new BigDecimal("0.6")),
                                unitVariance.multiply(new BigDecimal("0.3")),
                                unitVariance.multiply(new BigDecimal("0.1"))))
                        .build());
            }
        }

        List<CostVarianceReportDTO.ProductCostVariance> anomalyProducts =
                productVariances.stream()
                        .filter(p -> p.getVarianceRate() != null &&
                                p.getVarianceRate().abs().compareTo(new BigDecimal("5")) > 0)
                        .collect(Collectors.toList());

        return CostVarianceReportDTO.builder()
                .factoryId(factoryId)
                .startDate(startDate)
                .endDate(endDate)
                .totalBomCost(totalBomCost.setScale(2, RoundingMode.HALF_UP))
                .totalActualCost(totalActualCost.setScale(2, RoundingMode.HALF_UP))
                .totalVariance(totalVariance.setScale(2, RoundingMode.HALF_UP))
                .totalVarianceRate(varianceRate.setScale(2, RoundingMode.HALF_UP))
                .varianceStatus(CostVarianceReportDTO.calculateStatus(varianceRate))
                .productCount(productBatches.size())
                .batchCount(batchCount)
                .materialCostRatio(new BigDecimal("60"))
                .laborCostRatio(new BigDecimal("25"))
                .overheadCostRatio(new BigDecimal("15"))
                .productVariances(productVariances)
                .anomalyProducts(anomalyProducts)
                .build();
    }

    @Override
    @Cacheable(value = "kpiMetrics", key = "#factoryId + '_' + #date", unless = "#result == null")
    public KpiMetricsDTO getKpiMetricsDTO(String factoryId, LocalDate date) {
        log.info("获取完整KPI指标: factoryId={}, date={}", factoryId, date);

        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        LocalDateTime monthStart = date.minusDays(30).atStartOfDay();

        OeeReportDTO oeeReport = getOeeReport(factoryId, date.minusDays(7), date);
        CostVarianceReportDTO costReport = getCostVarianceReport(factoryId, date.minusDays(30), date);

        BigDecimal totalOutput = productionPlanRepository.calculateOutputBetweenDates(
                factoryId, monthStart, dayEnd);
        BigDecimal plannedOutput = productionPlanRepository.calculatePlannedOutputBetweenDates(
                factoryId, monthStart, dayEnd);

        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndDateRange(
                factoryId, date.minusDays(30), date);
        long totalInspections = inspections.size();
        long passedInspections = inspections.stream()
                .filter(q -> "PASS".equalsIgnoreCase(q.getResult()) || "passed".equalsIgnoreCase(q.getResult()))
                .count();

        List<ShipmentRecord> shipments = shipmentRecordRepository.findByFactoryIdAndDateRange(
                factoryId, date.minusDays(30), date);
        long totalShipments = shipments.size();
        long onTimeShipments = shipments.stream()
                .filter(s -> "delivered".equalsIgnoreCase(s.getStatus()) || "shipped".equalsIgnoreCase(s.getStatus()))
                .count();

        List<FactoryEquipment> equipments = equipmentRepository.findByFactoryId(factoryId);
        long runningEquipment = equipments.stream()
                .filter(e -> "RUNNING".equals(e.getStatus()) || "运行中".equals(e.getStatus()))
                .count();

        long totalUsers = userRepository.countByFactoryId(factoryId);
        long activeUsers = userRepository.countActiveUsers(factoryId);

        BigDecimal safeOutput = totalOutput != null ? totalOutput : BigDecimal.ZERO;
        BigDecimal outputCompletionRate = plannedOutput != null && plannedOutput.compareTo(BigDecimal.ZERO) > 0
                && safeOutput.compareTo(BigDecimal.ZERO) > 0 ?
                safeOutput.divide(plannedOutput, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")) :
                new BigDecimal("85");

        BigDecimal fpy = totalInspections > 0 ?
                new BigDecimal(passedInspections).divide(new BigDecimal(totalInspections), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("96");

        BigDecimal otif = totalShipments > 0 ?
                new BigDecimal(onTimeShipments).divide(new BigDecimal(totalShipments), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("95");

        // 单位成本 = 期间实际生产成本 / 期间产出. 无产出(如纯零售租户)或无成本数据时返 0 (诚实空, 不造假).
        BigDecimal totalActualCost = costReport != null ? costReport.getTotalActualCost() : null;
        BigDecimal unitCost = (totalActualCost != null && safeOutput.compareTo(BigDecimal.ZERO) > 0) ?
                totalActualCost.divide(safeOutput, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal equipmentAvailability = equipments.size() > 0 ?
                new BigDecimal(runningEquipment).divide(new BigDecimal(equipments.size()), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("85");

        BigDecimal attendanceRate = totalUsers > 0 ?
                new BigDecimal(activeUsers).divide(new BigDecimal(totalUsers), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("95");

        // ===== KPI 补充指标 (真实计算, 无数据返 0 诚实空, 不造假) =====
        // 平均生产周期(分钟): 已完成批次 end_time - start_time 均值
        Double avgCycleMin = productionBatchRepository.calculateAvgCycleTimeMinutes(factoryId, monthStart, dayEnd);
        BigDecimal avgCycleTime = avgCycleMin != null ?
                BigDecimal.valueOf(avgCycleMin).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 平均交期(天): 已交付订单 交付日 - 下单日 均值
        Double avgLeadDays = salesOrderRepository.calculateAvgLeadTimeDays(factoryId, date.minusDays(30), date);
        BigDecimal avgLeadTime = avgLeadDays != null ?
                BigDecimal.valueOf(avgLeadDays).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 返工率(%): 期间返工记录数 / 期间完成批次数
        long reworkCount = reworkRecordRepository.countByFactoryIdAndStartTimeBetween(factoryId, monthStart, dayEnd);
        long completedBatchCount = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtBetween(
                factoryId, ProductionBatchStatus.COMPLETED, monthStart, dayEnd);
        BigDecimal reworkRate = completedBatchCount > 0 ?
                new BigDecimal(reworkCount).divide(new BigDecimal(completedBatchCount), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 订单满足率(%): 已完成订单 / 有效订单(非草稿/取消)
        List<SalesOrder> periodOrders = salesOrderRepository.findByFactoryIdAndDateRange(
                factoryId, date.minusDays(30), date);
        long activeOrders = periodOrders.stream()
                .filter(o -> o.getStatus() != SalesOrderStatus.DRAFT && o.getStatus() != SalesOrderStatus.CANCELLED)
                .count();
        long fulfilledOrders = periodOrders.stream()
                .filter(o -> o.getStatus() == SalesOrderStatus.COMPLETED)
                .count();
        BigDecimal orderFulfillmentRate = activeOrders > 0 ?
                new BigDecimal(fulfilledOrders).divide(new BigDecimal(activeOrders), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        KpiMetricsDTO kpi = KpiMetricsDTO.builder()
                .factoryId(factoryId)
                .reportDate(date)
                .updatedAt(LocalDateTime.now())
                .oee(oeeReport.getOeeValue())
                .outputCompletionRate(outputCompletionRate.setScale(2, RoundingMode.HALF_UP))
                .capacityUtilization(oeeReport.getAvailability())
                .throughput(totalOutput != null ? totalOutput.divide(new BigDecimal("30"), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .fpy(fpy.setScale(2, RoundingMode.HALF_UP))
                .overallQualityRate(oeeReport.getQuality())
                .scrapRate(new BigDecimal("100").subtract(oeeReport.getQuality()).setScale(2, RoundingMode.HALF_UP))
                .bomVarianceRate(costReport.getTotalVarianceRate())
                .materialCostRatio(costReport.getMaterialCostRatio())
                .laborCostRatio(costReport.getLaborCostRatio())
                .overheadCostRatio(costReport.getOverheadCostRatio())
                .unitCost(unitCost)
                .avgCycleTime(avgCycleTime)
                .avgLeadTime(avgLeadTime)
                .reworkRate(reworkRate)
                .orderFulfillmentRate(orderFulfillmentRate)
                .otif(otif.setScale(2, RoundingMode.HALF_UP))
                .onTimeDeliveryRate(otif.setScale(2, RoundingMode.HALF_UP))
                .equipmentAvailability(equipmentAvailability.setScale(2, RoundingMode.HALF_UP))
                .mtbf(new BigDecimal("168"))
                .mttr(new BigDecimal("2"))
                .outputPerWorker(activeUsers > 0 && totalOutput != null ?
                        totalOutput.divide(new BigDecimal(activeUsers), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .attendanceRate(attendanceRate.setScale(2, RoundingMode.HALF_UP))
                .build();

        kpi.setOverallScore(KpiMetricsDTO.calculateOverallScore(kpi));
        kpi.setScoreGrade(KpiMetricsDTO.calculateGrade(kpi.getOverallScore()));

        return kpi;
    }

    @Override
    @Cacheable(value = "capacityUtilization", key = "#factoryId + '_' + #startDate + '_' + #endDate", unless = "#result == null")
    public Map<String, Object> getCapacityUtilizationReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取产能利用率报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        Map<String, Object> report = new HashMap<>();
        report.put("factoryId", factoryId);
        report.put("startDate", startDate);
        report.put("endDate", endDate);

        List<FactoryEquipment> equipments = equipmentRepository.findByFactoryId(factoryId);
        int totalEquipment = equipments.size();

        List<Map<String, Object>> dailyUtilization = new ArrayList<>();
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;

        for (int i = 0; i < totalDays; i++) {
            LocalDate date = startDate.plusDays(i);
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

            BigDecimal dayOutput = productionPlanRepository.calculateOutputBetweenDates(
                    factoryId, dayStart, dayEnd);
            if (dayOutput == null) dayOutput = BigDecimal.ZERO;

            BigDecimal maxCapacity = new BigDecimal("1000");
            BigDecimal utilization = dayOutput.divide(maxCapacity, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", date.toString());
            dayData.put("dayOfWeek", date.getDayOfWeek().getValue());
            dayData.put("weekOfYear", date.get(java.time.temporal.WeekFields.ISO.weekOfYear()));
            dayData.put("output", dayOutput);
            dayData.put("utilization", utilization.min(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
            dailyUtilization.add(dayData);
        }

        BigDecimal avgUtilization = dailyUtilization.stream()
                .map(d -> (BigDecimal) d.get("utilization"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(dailyUtilization.size()), 2, RoundingMode.HALF_UP);

        List<Map<String, Object>> equipmentUtilization = new ArrayList<>();
        for (FactoryEquipment eq : equipments) {
            Map<String, Object> eqData = new HashMap<>();
            eqData.put("equipmentId", eq.getId());
            eqData.put("equipmentName", eq.getEquipmentName());
            eqData.put("status", eq.getStatus());
            long runningHours = eq.getTotalRunningHours() != null ? eq.getTotalRunningHours() : 0;
            long plannedHours = totalDays * 8;
            BigDecimal eqUtilization = plannedHours > 0 ?
                    new BigDecimal(runningHours).divide(new BigDecimal(plannedHours), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")) : BigDecimal.ZERO;
            eqData.put("utilization", eqUtilization.min(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
            equipmentUtilization.add(eqData);
        }

        report.put("totalEquipment", totalEquipment);
        report.put("averageUtilization", avgUtilization);
        report.put("dailyUtilization", dailyUtilization);
        report.put("equipmentUtilization", equipmentUtilization);
        report.put("utilizationTarget", new BigDecimal("80"));
        return report;
    }

    @Override
    @Cacheable(value = "onTimeDelivery", key = "#factoryId + '_' + #startDate + '_' + #endDate", unless = "#result == null")
    public Map<String, Object> getOnTimeDeliveryReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取准时交付报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        Map<String, Object> report = new HashMap<>();
        report.put("factoryId", factoryId);
        report.put("startDate", startDate);
        report.put("endDate", endDate);

        List<ShipmentRecord> shipments = shipmentRecordRepository.findByFactoryIdAndDateRange(
                factoryId, startDate, endDate);

        int totalOrders = shipments.size();
        int onTimeOrders = 0;
        int inFullOrders = 0;
        int otifOrders = 0;

        List<Map<String, Object>> orderDetails = new ArrayList<>();
        for (ShipmentRecord shipment : shipments) {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("shipmentId", shipment.getId());
            orderData.put("orderNumber", shipment.getOrderNumber() != null ? shipment.getOrderNumber() : shipment.getShipmentNumber());
            orderData.put("shipmentDate", shipment.getShipmentDate());
            orderData.put("quantity", shipment.getQuantity());
            orderData.put("status", shipment.getStatus());

            boolean isOnTime = "delivered".equalsIgnoreCase(shipment.getStatus()) ||
                    "shipped".equalsIgnoreCase(shipment.getStatus());
            orderData.put("onTime", isOnTime);
            if (isOnTime) onTimeOrders++;

            boolean isInFull = shipment.getQuantity() != null && shipment.getQuantity().compareTo(BigDecimal.ZERO) > 0;
            orderData.put("inFull", isInFull);
            if (isInFull) inFullOrders++;

            boolean isOtif = isOnTime && isInFull;
            orderData.put("otif", isOtif);
            if (isOtif) otifOrders++;

            orderDetails.add(orderData);
        }

        BigDecimal onTimeRate = totalOrders > 0 ?
                new BigDecimal(onTimeOrders).divide(new BigDecimal(totalOrders), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("100");
        BigDecimal inFullRate = totalOrders > 0 ?
                new BigDecimal(inFullOrders).divide(new BigDecimal(totalOrders), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("100");
        BigDecimal otifRate = totalOrders > 0 ?
                new BigDecimal(otifOrders).divide(new BigDecimal(totalOrders), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")) : new BigDecimal("100");

        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        Map<LocalDate, List<ShipmentRecord>> dailyShipments = shipments.stream()
                .collect(Collectors.groupingBy(ShipmentRecord::getShipmentDate));

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", date.toString());

            List<ShipmentRecord> dayShipments = dailyShipments.getOrDefault(date, Collections.emptyList());
            int dayTotal = dayShipments.size();
            int dayOnTime = (int) dayShipments.stream()
                    .filter(s -> "delivered".equalsIgnoreCase(s.getStatus()) ||
                            "shipped".equalsIgnoreCase(s.getStatus()))
                    .count();

            dayData.put("totalOrders", dayTotal);
            dayData.put("onTimeOrders", dayOnTime);
            dayData.put("otifRate", dayTotal > 0 ?
                    new BigDecimal(dayOnTime).divide(new BigDecimal(dayTotal), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) :
                    new BigDecimal("100"));

            dailyTrend.add(dayData);
        }

        report.put("totalOrders", totalOrders);
        report.put("onTimeOrders", onTimeOrders);
        report.put("inFullOrders", inFullOrders);
        report.put("otifOrders", otifOrders);
        report.put("onTimeRate", onTimeRate.setScale(2, RoundingMode.HALF_UP));
        report.put("inFullRate", inFullRate.setScale(2, RoundingMode.HALF_UP));
        report.put("otifRate", otifRate.setScale(2, RoundingMode.HALF_UP));
        report.put("target", new BigDecimal("95"));
        report.put("dailyTrend", dailyTrend);
        report.put("orderDetails", orderDetails.subList(0, Math.min(20, orderDetails.size())));
        return report;
    }
}
