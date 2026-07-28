package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Cost Variance Report Tool
 *
 * Provides cost variance analysis reports including BOM theoretical cost vs actual cost comparison,
 * variance analysis, and cost anomaly detection.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Slf4j
@Component
public class ReportCostVarianceTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_cost_variance";
    }

    @Override
    public String getDescription() {
        return "获取成本差异分析报表，包含BOM理论成本与实际成本对比、差异分析、成本异常检测。" +
                "适用场景：成本控制、BOM优化、降本增效分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // period: 时间周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "时间周期：today(今日), week(本周), month(本月), quarter(本季度), year(本年)");
        period.put("enum", Arrays.asList("today", "week", "month", "quarter", "year"));
        period.put("default", "month");
        properties.put("period", period);

        // startDate: 开始日期（可选）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

        // productTypeId: 产品类型ID（可选）
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型ID，筛选特定产品的成本差异数据");
        properties.put("productTypeId", productTypeId);

        // varianceThreshold: 差异阈值百分比（可选）
        Map<String, Object> varianceThreshold = new HashMap<>();
        varianceThreshold.put("type", "number");
        varianceThreshold.put("description", "差异阈值百分比，只显示差异超过此阈值的数据（例如: 5 表示只显示差异超过5%的）");
        properties.put("varianceThreshold", varianceThreshold);

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
        log.info("执行成本差异报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "month");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String productTypeId = getString(params, "productTypeId");
        Double varianceThreshold = params.get("varianceThreshold") != null
                ? ((Number) params.get("varianceThreshold")).doubleValue()
                : null;

        // 计算日期范围
        LocalDate endDate = LocalDate.now();
        LocalDate startDate;

        if (startDateStr != null && endDateStr != null) {
            // 使用用户指定的日期范围
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } else {
            // 根据 period 计算日期范围
            startDate = calculateStartDate(period, endDate);
        }

        // 调用服务获取成本差异报表数据
        CostVarianceReportDTO costVarianceData = reportService.getCostVarianceReport(factoryId, startDate, endDate);

        // 如果指定了产品类型ID，过滤数据
        if (productTypeId != null && costVarianceData.getProductVariances() != null) {
            List<CostVarianceReportDTO.ProductCostVariance> filteredProducts = costVarianceData.getProductVariances().stream()
                    .filter(p -> productTypeId.equals(p.getProductTypeId()))
                    .toList();
            costVarianceData.setProductVariances(filteredProducts);
        }

        // 如果指定了差异阈值，过滤异常产品
        if (varianceThreshold != null && costVarianceData.getProductVariances() != null) {
            List<CostVarianceReportDTO.ProductCostVariance> filteredProducts = costVarianceData.getProductVariances().stream()
                    .filter(p -> p.getVarianceRate() != null &&
                                 Math.abs(p.getVarianceRate().doubleValue()) >= varianceThreshold)
                    .toList();
            costVarianceData.setProductVariances(filteredProducts);
        }

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "COST_VARIANCE");
        result.put("period", period);

        // 日期范围
        Map<String, String> dateRange = new HashMap<>();
        dateRange.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        dateRange.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        result.put("dateRange", dateRange);

        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", costVarianceData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        queryConditions.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        if (productTypeId != null) queryConditions.put("productTypeId", productTypeId);
        if (varianceThreshold != null) queryConditions.put("varianceThreshold", varianceThreshold);
        result.put("queryConditions", queryConditions);

        log.info("成本差异报表查询完成 - 工厂ID: {}, 日期范围: {} ~ {}", factoryId, startDate, endDate);

        return result;
    }

    /**
     * 根据周期计算开始日期
     */
    private LocalDate calculateStartDate(String period, LocalDate endDate) {
        switch (period.toLowerCase()) {
            case "today":
                return endDate;
            case "week":
                return endDate.minusDays(endDate.getDayOfWeek().getValue() - 1);
            case "month":
                return endDate.withDayOfMonth(1);
            case "quarter":
                int quarterStartMonth = ((endDate.getMonthValue() - 1) / 3) * 3 + 1;
                return endDate.withMonth(quarterStartMonth).withDayOfMonth(1);
            case "year":
                return endDate.withDayOfYear(1);
            default:
                return endDate.withDayOfMonth(1); // 默认本月
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的成本差异数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "productTypeId", "请问要查看哪个产品的成本差异？如需全部产品可不指定。",
            "varianceThreshold", "请问要筛选差异超过多少百分比的数据？（例如输入5表示只显示差异超过5%的）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "productTypeId", "产品类型ID",
            "varianceThreshold", "差异阈值"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
