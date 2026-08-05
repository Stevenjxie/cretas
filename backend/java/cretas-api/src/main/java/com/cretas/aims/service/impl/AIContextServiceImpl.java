package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.ai.ProductionAIContext;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.AIContextService;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 上下文服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-13
 */
@Slf4j
@Service
public class AIContextServiceImpl implements AIContextService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BomService bomService;
    private final ReportService reportService;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductTypeRepository productTypeRepository;

    public AIContextServiceImpl(BomService bomService,
                                @Lazy ReportService reportService,
                                ProductionBatchRepository productionBatchRepository,
                                ProductTypeRepository productTypeRepository) {
        this.bomService = bomService;
        this.reportService = reportService;
        this.productionBatchRepository = productionBatchRepository;
        this.productTypeRepository = productTypeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionAIContext buildProductionContext(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("构建生产AI上下文: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        // 1. 获取按产品分组的生产统计
        List<ProductionByProductDTO> productionStats = reportService.getProductionByProduct(
                factoryId, startDate, endDate);

        // 2. 获取有 BOM 配置的产品
        List<String> productTypesWithBom = bomService.getProductTypesWithBom(factoryId);

        // 3. 获取产品信息（含分类）
        Map<String, ProductType> productTypeMap = productTypeRepository.findByFactoryId(factoryId)
                .stream()
                .collect(Collectors.toMap(ProductType::getId, p -> p, (a, b) -> a));

        // 4. 获取批次成本数据
        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                factoryId, startDateTime, endDateTime);

        // 按产品分组的批次成本
        Map<String, List<ProductionBatch>> batchesByProduct = batches.stream()
                .filter(b -> b.getProductTypeId() != null)
                .collect(Collectors.groupingBy(ProductionBatch::getProductTypeId));

        // 5. 构建带成本信息的生产统计
        List<ProductionAIContext.ProductionWithCostDTO> productionWithCost = new ArrayList<>();
        BigDecimal totalOutput = BigDecimal.ZERO;
        BigDecimal totalMaterialCost = BigDecimal.ZERO;
        BigDecimal totalLaborCost = BigDecimal.ZERO;
        BigDecimal totalEquipmentCost = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ProductionByProductDTO stat : productionStats) {
            String productTypeId = stat.getProductTypeId();
            ProductType productType = productTypeMap.get(productTypeId);

            // BOM 成本
            BigDecimal bomUnitCost = BigDecimal.ZERO;
            if (productTypesWithBom.contains(productTypeId)) {
                try {
                    BomCostSummaryDTO bomCost = bomService.calculateProductCost(factoryId, productTypeId);
                    // BOM 成本口径 = 辅料 + 包材。原料无数量、人工与均摊等结算，
                    // 故此值不是完全成本，不能与批次实际成本直接比高低。
                    bomUnitCost = bomCost.getTotalCost();
                } catch (Exception e) {
                    log.warn("计算BOM成本失败: productTypeId={}", productTypeId, e);
                }
            }

            // 实际成本统计
            List<ProductionBatch> productBatches = batchesByProduct.getOrDefault(productTypeId, Collections.emptyList());
            BigDecimal avgActualUnitCost = BigDecimal.ZERO;
            BigDecimal productTotalCost = BigDecimal.ZERO;
            BigDecimal productMaterialCost = BigDecimal.ZERO;
            BigDecimal productLaborCost = BigDecimal.ZERO;
            BigDecimal productEquipmentCost = BigDecimal.ZERO;

            if (!productBatches.isEmpty()) {
                for (ProductionBatch batch : productBatches) {
                    if (batch.getTotalCost() != null) {
                        productTotalCost = productTotalCost.add(batch.getTotalCost());
                    }
                    if (batch.getMaterialCost() != null) {
                        productMaterialCost = productMaterialCost.add(batch.getMaterialCost());
                    }
                    if (batch.getLaborCost() != null) {
                        productLaborCost = productLaborCost.add(batch.getLaborCost());
                    }
                    if (batch.getEquipmentCost() != null) {
                        productEquipmentCost = productEquipmentCost.add(batch.getEquipmentCost());
                    }
                }

                // 计算平均单位成本
                BigDecimal totalQuantity = productBatches.stream()
                        .map(b -> b.getActualQuantity() != null ? b.getActualQuantity() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (totalQuantity.compareTo(BigDecimal.ZERO) > 0) {
                    avgActualUnitCost = productTotalCost.divide(totalQuantity, 4, RoundingMode.HALF_UP);
                }
            }

            // 成本差异
            BigDecimal costVariance = avgActualUnitCost.subtract(bomUnitCost);
            BigDecimal costVarianceRate = BigDecimal.ZERO;
            if (bomUnitCost.compareTo(BigDecimal.ZERO) > 0) {
                costVarianceRate = costVariance.divide(bomUnitCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            ProductionAIContext.ProductionWithCostDTO dto = ProductionAIContext.ProductionWithCostDTO.builder()
                    .productTypeId(productTypeId)
                    .productName(stat.getProductName())
                    .productCategory(productType != null ? productType.getProductCategory() : null)
                    .totalQuantity(stat.getTotalQuantity())
                    .unit(stat.getUnit())
                    .bomUnitCost(bomUnitCost)
                    .avgActualUnitCost(avgActualUnitCost)
                    .costVariance(costVariance)
                    .costVarianceRate(costVarianceRate)
                    .totalCost(productTotalCost)
                    .batchCount(productBatches.size())
                    .build();

            productionWithCost.add(dto);

            // 累计总数
            totalOutput = totalOutput.add(stat.getTotalQuantity() != null ? stat.getTotalQuantity() : BigDecimal.ZERO);
            totalMaterialCost = totalMaterialCost.add(productMaterialCost);
            totalLaborCost = totalLaborCost.add(productLaborCost);
            totalEquipmentCost = totalEquipmentCost.add(productEquipmentCost);
            totalCost = totalCost.add(productTotalCost);
        }

        // 6. 计算成本结构
        Map<String, BigDecimal> costBreakdown = new LinkedHashMap<>();
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            costBreakdown.put("material", totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")));
            costBreakdown.put("labor", totalLaborCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")));
            costBreakdown.put("equipment", totalEquipmentCost.divide(totalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")));
            BigDecimal otherRatio = new BigDecimal("100")
                    .subtract(costBreakdown.get("material"))
                    .subtract(costBreakdown.get("labor"))
                    .subtract(costBreakdown.get("equipment"));
            costBreakdown.put("other", otherRatio.max(BigDecimal.ZERO));
        }

        // 7. 排名
        List<String> topByOutput = productionWithCost.stream()
                .sorted(Comparator.comparing(ProductionAIContext.ProductionWithCostDTO::getTotalQuantity,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(ProductionAIContext.ProductionWithCostDTO::getProductName)
                .collect(Collectors.toList());

        List<String> topByCost = productionWithCost.stream()
                .sorted(Comparator.comparing(ProductionAIContext.ProductionWithCostDTO::getTotalCost,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(ProductionAIContext.ProductionWithCostDTO::getProductName)
                .collect(Collectors.toList());

        List<String> topByVariance = productionWithCost.stream()
                .filter(p -> p.getCostVarianceRate() != null)
                .sorted(Comparator.comparing(p -> p.getCostVarianceRate().abs(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(ProductionAIContext.ProductionWithCostDTO::getProductName)
                .collect(Collectors.toList());

        // 8. 计算日均产量
        long periodDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        BigDecimal avgDailyOutput = totalOutput.divide(new BigDecimal(periodDays), 2, RoundingMode.HALF_UP);
        BigDecimal avgUnitCost = totalOutput.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalOutput, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return ProductionAIContext.builder()
                .factoryId(factoryId)
                .startDate(startDate)
                .endDate(endDate)
                .periodDays((int) periodDays)
                .productionByProduct(productionWithCost)
                .totalOutput(totalOutput)
                .productCount(productionWithCost.size())
                .avgDailyOutput(avgDailyOutput)
                .totalMaterialCost(totalMaterialCost)
                .totalLaborCost(totalLaborCost)
                .totalEquipmentCost(totalEquipmentCost)
                .totalCost(totalCost)
                .avgUnitCost(avgUnitCost)
                .costBreakdown(costBreakdown)
                .topProductsByOutput(topByOutput)
                .topProductsByCost(topByCost)
                .topProductsByCostVariance(topByVariance)
                .calculatedAt(LocalDateTime.now().format(DATE_FORMATTER))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CostAIContext buildCostContext(String factoryId, String productTypeId, Integer recentBatchCount) {
        log.info("构建成本AI上下文: factoryId={}, productTypeId={}, recentBatchCount={}",
                factoryId, productTypeId, recentBatchCount);

        if (recentBatchCount == null || recentBatchCount <= 0) {
            recentBatchCount = 10;
        }

        // 1. 获取产品信息
        ProductType productType = productTypeRepository.findById(productTypeId).orElse(null);

        // 2. 获取 BOM 成本
        BomCostSummaryDTO bomCost = null;
        BigDecimal bomTotalCost = BigDecimal.ZERO;
        try {
            bomCost = bomService.calculateProductCost(factoryId, productTypeId);
            // BOM 成本口径 = 辅料 + 包材。原料无数量、人工与均摊等结算，
            // 故此值不是完全成本，不能与批次实际成本直接比高低。
            bomTotalCost = bomCost.getTotalCost();
        } catch (Exception e) {
            log.warn("获取BOM成本失败: productTypeId={}", productTypeId, e);
        }

        // 3. 获取最近批次
        // 使用时间范围查询代替，获取最近3个月的数据
        LocalDateTime threeMonthsAgo = LocalDateTime.now().minusMonths(3);
        List<ProductionBatch> allBatches = productionBatchRepository.findByFactoryIdAndCreatedAtAfter(
                factoryId, threeMonthsAgo);

        List<ProductionBatch> productBatches = allBatches.stream()
                .filter(b -> productTypeId.equals(b.getProductTypeId()))
                .sorted(Comparator.comparing(ProductionBatch::getCreatedAt).reversed())
                .limit(recentBatchCount)
                .collect(Collectors.toList());

        // 4. 计算实际成本统计
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalActualCost = BigDecimal.ZERO;
        BigDecimal totalMaterialCost = BigDecimal.ZERO;
        BigDecimal totalLaborCost = BigDecimal.ZERO;
        BigDecimal totalEquipmentCost = BigDecimal.ZERO;
        BigDecimal totalOtherCost = BigDecimal.ZERO;

        for (ProductionBatch batch : productBatches) {
            if (batch.getActualQuantity() != null) {
                totalQuantity = totalQuantity.add(batch.getActualQuantity());
            }
            if (batch.getTotalCost() != null) {
                totalActualCost = totalActualCost.add(batch.getTotalCost());
            }
            if (batch.getMaterialCost() != null) {
                totalMaterialCost = totalMaterialCost.add(batch.getMaterialCost());
            }
            if (batch.getLaborCost() != null) {
                totalLaborCost = totalLaborCost.add(batch.getLaborCost());
            }
            if (batch.getEquipmentCost() != null) {
                totalEquipmentCost = totalEquipmentCost.add(batch.getEquipmentCost());
            }
            if (batch.getOtherCost() != null) {
                totalOtherCost = totalOtherCost.add(batch.getOtherCost());
            }
        }

        BigDecimal avgActualUnitCost = totalQuantity.compareTo(BigDecimal.ZERO) > 0
                ? totalActualCost.divide(totalQuantity, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 5. 计算成本占比
        BigDecimal actualMaterialRatio = BigDecimal.ZERO;
        BigDecimal actualLaborRatio = BigDecimal.ZERO;
        BigDecimal actualEquipmentRatio = BigDecimal.ZERO;
        BigDecimal actualOtherRatio = BigDecimal.ZERO;

        if (totalActualCost.compareTo(BigDecimal.ZERO) > 0) {
            actualMaterialRatio = totalMaterialCost.divide(totalActualCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            actualLaborRatio = totalLaborCost.divide(totalActualCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            actualEquipmentRatio = totalEquipmentCost.divide(totalActualCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            actualOtherRatio = totalOtherCost.divide(totalActualCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        // BOM 成本占比
        BigDecimal bomMaterialRatio = BigDecimal.ZERO;
        BigDecimal bomLaborRatio = BigDecimal.ZERO;
        BigDecimal bomOverheadRatio = BigDecimal.ZERO;
        if (bomCost != null && bomTotalCost.compareTo(BigDecimal.ZERO) > 0) {
            bomMaterialRatio = bomCost.getMaterialCostTotal().divide(bomTotalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            // 人工/均摊已移出 BOM 归集（getLaborCostTotal()/getOverheadCostTotal() 现为 null，
            // 非 0），null-safe 按 0 处理：BOM 口径下这两项占比恒为 0，不代表实际不产生人工/均摊。
            BigDecimal bomLaborTotal = bomCost.getLaborCostTotal() != null
                    ? bomCost.getLaborCostTotal() : BigDecimal.ZERO;
            BigDecimal bomOverheadTotal = bomCost.getOverheadCostTotal() != null
                    ? bomCost.getOverheadCostTotal() : BigDecimal.ZERO;
            bomLaborRatio = bomLaborTotal.divide(bomTotalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            bomOverheadRatio = bomOverheadTotal.divide(bomTotalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        // 6. 成本差异
        BigDecimal costVariance = avgActualUnitCost.subtract(bomTotalCost);
        BigDecimal costVarianceRate = BigDecimal.ZERO;
        if (bomTotalCost.compareTo(BigDecimal.ZERO) > 0) {
            costVarianceRate = costVariance.divide(bomTotalCost, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        String varianceStatus = "NORMAL";
        if (costVarianceRate.abs().compareTo(new BigDecimal("20")) > 0) {
            varianceStatus = "CRITICAL";
        } else if (costVarianceRate.abs().compareTo(new BigDecimal("10")) > 0) {
            varianceStatus = "WARNING";
        }

        // 7. 差异明细
        List<CostAIContext.CostVarianceDetail> varianceDetails = buildVarianceDetails(
                bomCost, totalMaterialCost, totalLaborCost, totalEquipmentCost,
                productBatches.size(), bomTotalCost);

        // 8. 批次成本趋势
        final BigDecimal finalBomTotalCost = bomTotalCost;
        List<CostAIContext.BatchCostTrend> trends = productBatches.stream()
                .map(batch -> {
                    BigDecimal unitCost = BigDecimal.ZERO;
                    if (batch.getActualQuantity() != null && batch.getActualQuantity().compareTo(BigDecimal.ZERO) > 0
                            && batch.getTotalCost() != null) {
                        unitCost = batch.getTotalCost().divide(batch.getActualQuantity(), 4, RoundingMode.HALF_UP);
                    }
                    BigDecimal varRate = BigDecimal.ZERO;
                    if (finalBomTotalCost.compareTo(BigDecimal.ZERO) > 0) {
                        varRate = unitCost.subtract(finalBomTotalCost).divide(finalBomTotalCost, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                    }
                    return CostAIContext.BatchCostTrend.builder()
                            .batchNumber(batch.getBatchNumber())
                            .batchDate(batch.getCreatedAt() != null
                                    ? batch.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                    : null)
                            .unitCost(unitCost)
                            .varianceRate(varRate)
                            .build();
                })
                .collect(Collectors.toList());

        // 判断成本趋势
        Boolean isCostIncreasing = false;
        if (trends.size() >= 3) {
            BigDecimal firstHalfAvg = trends.subList(trends.size() / 2, trends.size()).stream()
                    .map(CostAIContext.BatchCostTrend::getUnitCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(new BigDecimal(trends.size() - trends.size() / 2), 4, RoundingMode.HALF_UP);
            BigDecimal secondHalfAvg = trends.subList(0, trends.size() / 2).stream()
                    .map(CostAIContext.BatchCostTrend::getUnitCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(new BigDecimal(trends.size() / 2), 4, RoundingMode.HALF_UP);
            isCostIncreasing = secondHalfAvg.compareTo(firstHalfAvg) > 0;
        }

        return CostAIContext.builder()
                .factoryId(factoryId)
                .productTypeId(productTypeId)
                .productName(productType != null ? productType.getName() : null)
                .productCategory(productType != null ? productType.getProductCategory() : null)
                .bomCostSummary(bomCost)
                .bomTotalCost(bomTotalCost)
                .bomMaterialCostRatio(bomMaterialRatio)
                .bomLaborCostRatio(bomLaborRatio)
                .bomOverheadCostRatio(bomOverheadRatio)
                .batchCount(productBatches.size())
                .totalQuantity(totalQuantity)
                .avgActualUnitCost(avgActualUnitCost)
                .actualMaterialCostRatio(actualMaterialRatio)
                .actualLaborCostRatio(actualLaborRatio)
                .actualEquipmentCostRatio(actualEquipmentRatio)
                .actualOtherCostRatio(actualOtherRatio)
                .costVariance(costVariance)
                .costVarianceRate(costVarianceRate)
                .varianceStatus(varianceStatus)
                .varianceDetails(varianceDetails)
                .recentBatchTrends(trends)
                .isCostIncreasing(isCostIncreasing)
                .calculatedAt(LocalDateTime.now().format(DATE_FORMATTER))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostAIContext.CostVarianceDetail> getCostVarianceSummary(String factoryId) {
        // 获取所有有 BOM 的产品
        List<String> productTypeIds = bomService.getProductTypesWithBom(factoryId);
        List<CostAIContext.CostVarianceDetail> results = new ArrayList<>();

        for (String productTypeId : productTypeIds) {
            try {
                CostAIContext context = buildCostContext(factoryId, productTypeId, 10);
                if (context.getVarianceDetails() != null && !context.getVarianceDetails().isEmpty()) {
                    // 添加产品标识
                    for (CostAIContext.CostVarianceDetail detail : context.getVarianceDetails()) {
                        if (detail.getIsPrimarySource() != null && detail.getIsPrimarySource()) {
                            results.add(detail);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取产品成本差异失败: productTypeId={}", productTypeId, e);
            }
        }

        return results;
    }

    @Override
    public String formatProductionContextForPrompt(ProductionAIContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 预计算生产数据（").append(context.getStartDate())
          .append(" 至 ").append(context.getEndDate()).append("）\n\n");

        sb.append("### 总体指标\n");
        sb.append("- 总产量: ").append(context.getTotalOutput()).append("\n");
        sb.append("- 产品种类: ").append(context.getProductCount()).append("\n");
        sb.append("- 日均产量: ").append(context.getAvgDailyOutput()).append("\n");
        sb.append("- 总成本: ¥").append(context.getTotalCost()).append("\n");
        sb.append("- 平均单位成本: ¥").append(context.getAvgUnitCost()).append("\n\n");

        if (context.getCostBreakdown() != null && !context.getCostBreakdown().isEmpty()) {
            sb.append("### 成本结构\n");
            context.getCostBreakdown().forEach((key, value) ->
                    sb.append("- ").append(key).append(": ").append(value).append("%\n"));
            sb.append("\n");
        }

        sb.append("### 按产品明细\n");
        sb.append("| 产品 | 产量 | BOM成本 | 实际成本 | 差异率 |\n");
        sb.append("|------|------|---------|----------|--------|\n");
        if (context.getProductionByProduct() != null) {
            for (ProductionAIContext.ProductionWithCostDTO p : context.getProductionByProduct()) {
                sb.append("| ").append(p.getProductName())
                  .append(" | ").append(p.getTotalQuantity()).append(" ").append(p.getUnit())
                  .append(" | ¥").append(p.getBomUnitCost())
                  .append(" | ¥").append(p.getAvgActualUnitCost())
                  .append(" | ").append(p.getCostVarianceRate()).append("% |\n");
            }
        }

        return sb.toString();
    }

    @Override
    public String formatCostContextForPrompt(CostAIContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 产品成本分析: ").append(context.getProductName()).append("\n\n");

        sb.append("### BOM 理论成本\n");
        sb.append("- 总成本: ¥").append(context.getBomTotalCost()).append("/单位\n");
        sb.append("- 原料占比: ").append(context.getBomMaterialCostRatio()).append("%\n");
        sb.append("- 人工占比: ").append(context.getBomLaborCostRatio()).append("%\n");
        sb.append("- 均摊占比: ").append(context.getBomOverheadCostRatio()).append("%\n\n");

        sb.append("### 实际成本（最近").append(context.getBatchCount()).append("批次）\n");
        sb.append("- 平均单位成本: ¥").append(context.getAvgActualUnitCost()).append("\n");
        sb.append("- 原料占比: ").append(context.getActualMaterialCostRatio()).append("%\n");
        sb.append("- 人工占比: ").append(context.getActualLaborCostRatio()).append("%\n\n");

        sb.append("### 成本差异\n");
        sb.append("- 差异金额: ¥").append(context.getCostVariance()).append("\n");
        sb.append("- 差异率: ").append(context.getCostVarianceRate()).append("%\n");
        sb.append("- 状态: ").append(context.getVarianceStatus()).append("\n");
        sb.append("- 趋势: ").append(context.getIsCostIncreasing() ? "成本上升" : "成本平稳/下降").append("\n");

        return sb.toString();
    }

    private List<CostAIContext.CostVarianceDetail> buildVarianceDetails(
            BomCostSummaryDTO bomCost,
            BigDecimal actualMaterial, BigDecimal actualLabor, BigDecimal actualEquipment,
            int batchCount, BigDecimal bomTotal) {

        List<CostAIContext.CostVarianceDetail> details = new ArrayList<>();

        if (bomCost == null || batchCount == 0) {
            return details;
        }

        BigDecimal bomMaterial = bomCost.getMaterialCostTotal();
        // 人工已移出 BOM 归集（getLaborCostTotal() 现为 null，非 0），null-safe 按 0 处理：
        // 下方"人工差异"实为"实际人工 - 0"，即全部实际人工都会显示为差异，这是口径变化的
        // 自然结果，不是新增行为。
        BigDecimal bomLabor = bomCost.getLaborCostTotal() != null
                ? bomCost.getLaborCostTotal() : BigDecimal.ZERO;

        // 平均到单位
        BigDecimal avgActualMaterial = actualMaterial.divide(new BigDecimal(batchCount), 4, RoundingMode.HALF_UP);
        BigDecimal avgActualLabor = actualLabor.divide(new BigDecimal(batchCount), 4, RoundingMode.HALF_UP);

        // 原料差异
        BigDecimal materialVariance = avgActualMaterial.subtract(bomMaterial);
        BigDecimal materialVarianceRate = bomMaterial.compareTo(BigDecimal.ZERO) > 0
                ? materialVariance.divide(bomMaterial, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        details.add(CostAIContext.CostVarianceDetail.builder()
                .costType("MATERIAL")
                .costTypeName("原料成本")
                .bomValue(bomMaterial)
                .actualValue(avgActualMaterial)
                .variance(materialVariance)
                .varianceRate(materialVarianceRate)
                .isPrimarySource(materialVarianceRate.abs().compareTo(new BigDecimal("10")) > 0)
                .build());

        // 人工差异
        BigDecimal laborVariance = avgActualLabor.subtract(bomLabor);
        BigDecimal laborVarianceRate = bomLabor.compareTo(BigDecimal.ZERO) > 0
                ? laborVariance.divide(bomLabor, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        details.add(CostAIContext.CostVarianceDetail.builder()
                .costType("LABOR")
                .costTypeName("人工成本")
                .bomValue(bomLabor)
                .actualValue(avgActualLabor)
                .variance(laborVariance)
                .varianceRate(laborVarianceRate)
                .isPrimarySource(laborVarianceRate.abs().compareTo(new BigDecimal("10")) > 0)
                .build());

        return details;
    }
}
