package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.ai.ProductionAIContext;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.service.AIContextService;
import com.cretas.aims.service.BomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BOM 成本分析 AI Tool
 *
 * 提供 BOM 理论成本与实际成本的对比分析，用于：
 * 1. 成本异常检测
 * 2. 成本差异分析
 * 3. 成本优化建议生成
 *
 * 核心价值：预计算数据注入 AI 上下文，减少 LLM Token 消耗 50%+
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-13
 */
@Slf4j
@Component
public class ReportBomCostTool extends AbstractBusinessTool {

    @Autowired
    private BomService bomService;

    @Autowired
    private AIContextService aiContextService;

    @Override
    public String getToolName() {
        return "report_bom_cost";
    }

    @Override
    public String getDescription() {
        return "获取 BOM 成本分析报表，包含理论成本与实际成本对比、成本差异分析、成本异常检测。" +
                "适用场景：成本核算、差异分析、成本优化、报价参考。" +
                "数据已预计算，可直接用于分析，无需额外聚合。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // productTypeId: 产品类型ID（可选）
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID，指定时返回单个产品的详细成本分析");
        properties.put("productTypeId", productTypeId);

        // period: 时间周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "时间周期：today(今日), week(本周), month(本月)，用于生产统计");
        period.put("enum", Arrays.asList("today", "week", "month"));
        period.put("default", "week");
        properties.put("period", period);

        // analysisType: 分析类型
        Map<String, Object> analysisType = new HashMap<>();
        analysisType.put("type", "string");
        analysisType.put("description", "分析类型：summary(汇总), detail(详细), variance(差异分析)");
        analysisType.put("enum", Arrays.asList("summary", "detail", "variance"));
        analysisType.put("default", "summary");
        properties.put("analysisType", analysisType);

        // includeZeroCost: 是否包含零成本产品
        Map<String, Object> includeZeroCost = new HashMap<>();
        includeZeroCost.put("type", "boolean");
        includeZeroCost.put("description", "是否包含零成本产品");
        includeZeroCost.put("default", false);
        properties.put("includeZeroCost", includeZeroCost);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行 BOM 成本分析 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        String productTypeId = getString(params, "productTypeId");
        String period = getString(params, "period", "week");
        String analysisType = getString(params, "analysisType", "summary");
        Boolean includeZeroCost = getBoolean(params, "includeZeroCost", false);

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "BOM_COST");
        result.put("factoryId", factoryId);
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));

        if (productTypeId != null && !productTypeId.isEmpty()) {
            // 单个产品详细分析
            result.putAll(executeProductCostAnalysis(factoryId, productTypeId));
        } else {
            // 全厂汇总分析
            result.putAll(executeFactoryCostAnalysis(factoryId, period, analysisType, includeZeroCost));
        }

        log.info("BOM 成本分析完成 - 工厂ID: {}, 分析类型: {}", factoryId, analysisType);

        return result;
    }

    /**
     * 单个产品成本分析
     */
    private Map<String, Object> executeProductCostAnalysis(String factoryId, String productTypeId) {
        Map<String, Object> result = new HashMap<>();

        // 获取预计算的成本上下文
        CostAIContext costContext = aiContextService.buildCostContext(factoryId, productTypeId, 10);

        result.put("analysisType", "product_detail");
        result.put("productTypeId", productTypeId);
        result.put("productName", costContext.getProductName());
        result.put("productCategory", costContext.getProductCategory());

        // BOM 理论成本
        Map<String, Object> bomCost = new HashMap<>();
        bomCost.put("totalCost", costContext.getBomTotalCost());
        bomCost.put("materialRatio", costContext.getBomMaterialCostRatio());
        bomCost.put("laborRatio", costContext.getBomLaborCostRatio());
        bomCost.put("overheadRatio", costContext.getBomOverheadCostRatio());
        result.put("bomCost", bomCost);

        // 实际成本统计
        Map<String, Object> actualCost = new HashMap<>();
        actualCost.put("batchCount", costContext.getBatchCount());
        actualCost.put("totalQuantity", costContext.getTotalQuantity());
        actualCost.put("avgUnitCost", costContext.getAvgActualUnitCost());
        actualCost.put("materialRatio", costContext.getActualMaterialCostRatio());
        actualCost.put("laborRatio", costContext.getActualLaborCostRatio());
        actualCost.put("equipmentRatio", costContext.getActualEquipmentCostRatio());
        result.put("actualCost", actualCost);

        // 成本差异
        Map<String, Object> variance = new HashMap<>();
        variance.put("amount", costContext.getCostVariance());
        variance.put("rate", costContext.getCostVarianceRate());
        variance.put("status", costContext.getVarianceStatus());
        variance.put("details", costContext.getVarianceDetails());
        result.put("variance", variance);

        // 成本趋势
        Map<String, Object> trend = new HashMap<>();
        trend.put("isIncreasing", costContext.getIsCostIncreasing());
        trend.put("recentBatches", costContext.getRecentBatchTrends());
        result.put("trend", trend);

        // 预格式化的 AI 提示文本（减少 LLM 处理）
        result.put("preformattedPrompt", aiContextService.formatCostContextForPrompt(costContext));

        return result;
    }

    /**
     * 全厂成本汇总分析
     */
    private Map<String, Object> executeFactoryCostAnalysis(String factoryId, String period,
                                                           String analysisType, Boolean includeZeroCost) {
        Map<String, Object> result = new HashMap<>();

        // 计算日期范围
        LocalDate endDate = LocalDate.now();
        LocalDate startDate;
        switch (period) {
            case "today":
                startDate = endDate;
                break;
            case "month":
                startDate = endDate.minusMonths(1);
                break;
            case "week":
            default:
                startDate = endDate.minusWeeks(1);
        }

        result.put("analysisType", analysisType);
        result.put("period", period);
        result.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        result.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));

        // 获取预计算的生产上下文
        ProductionAIContext productionContext = aiContextService.buildProductionContext(
                factoryId, startDate, endDate);

        // 汇总数据
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalOutput", productionContext.getTotalOutput());
        summary.put("productCount", productionContext.getProductCount());
        summary.put("avgDailyOutput", productionContext.getAvgDailyOutput());
        summary.put("totalCost", productionContext.getTotalCost());
        summary.put("avgUnitCost", productionContext.getAvgUnitCost());
        summary.put("costBreakdown", productionContext.getCostBreakdown());
        result.put("summary", summary);

        // 排名信息
        Map<String, Object> rankings = new HashMap<>();
        rankings.put("topByOutput", productionContext.getTopProductsByOutput());
        rankings.put("topByCost", productionContext.getTopProductsByCost());
        rankings.put("topByCostVariance", productionContext.getTopProductsByCostVariance());
        result.put("rankings", rankings);

        // 按产品明细（根据分析类型决定是否包含）
        if ("detail".equals(analysisType) || "variance".equals(analysisType)) {
            List<Map<String, Object>> productDetails = new ArrayList<>();

            for (ProductionAIContext.ProductionWithCostDTO prod : productionContext.getProductionByProduct()) {
                // 过滤零成本产品
                if (!includeZeroCost && (prod.getBomUnitCost() == null ||
                        prod.getBomUnitCost().compareTo(BigDecimal.ZERO) == 0)) {
                    continue;
                }

                Map<String, Object> prodData = new HashMap<>();
                prodData.put("productTypeId", prod.getProductTypeId());
                prodData.put("productName", prod.getProductName());
                prodData.put("productCategory", prod.getProductCategory());
                prodData.put("totalQuantity", prod.getTotalQuantity());
                prodData.put("unit", prod.getUnit());
                prodData.put("bomUnitCost", prod.getBomUnitCost());
                prodData.put("avgActualUnitCost", prod.getAvgActualUnitCost());
                prodData.put("costVariance", prod.getCostVariance());
                prodData.put("costVarianceRate", prod.getCostVarianceRate());
                prodData.put("totalCost", prod.getTotalCost());
                prodData.put("batchCount", prod.getBatchCount());

                productDetails.add(prodData);
            }

            // 如果是差异分析，按差异率排序
            if ("variance".equals(analysisType)) {
                productDetails.sort((a, b) -> {
                    BigDecimal rateA = (BigDecimal) a.getOrDefault("costVarianceRate", BigDecimal.ZERO);
                    BigDecimal rateB = (BigDecimal) b.getOrDefault("costVarianceRate", BigDecimal.ZERO);
                    return rateB.abs().compareTo(rateA.abs());
                });
            }

            result.put("productDetails", productDetails);
        }

        // 成本异常产品（差异率 > 10%）
        List<Map<String, Object>> anomalies = productionContext.getProductionByProduct().stream()
                .filter(p -> p.getCostVarianceRate() != null &&
                        p.getCostVarianceRate().abs().compareTo(new BigDecimal("10")) > 0)
                .map(p -> {
                    Map<String, Object> anomaly = new HashMap<>();
                    anomaly.put("productName", p.getProductName());
                    anomaly.put("varianceRate", p.getCostVarianceRate());
                    anomaly.put("status", p.getCostVarianceRate().compareTo(BigDecimal.ZERO) > 0 ? "超支" : "节省");
                    return anomaly;
                })
                .collect(Collectors.toList());
        result.put("costAnomalies", anomalies);

        // 预格式化的 AI 提示文本
        result.put("preformattedPrompt", aiContextService.formatProductionContextForPrompt(productionContext));

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "productTypeId", "请问您想分析哪个产品的成本？请提供产品ID或名称。",
            "period", "请问您想查看哪个时间段的数据？可选：今日、本周、本月。",
            "analysisType", "请问需要哪种分析？可选：汇总、详细、差异分析。",
            "includeZeroCost", "是否需要包含零成本的产品？"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "productTypeId", "产品ID",
            "period", "时间周期",
            "analysisType", "分析类型",
            "includeZeroCost", "包含零成本"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
