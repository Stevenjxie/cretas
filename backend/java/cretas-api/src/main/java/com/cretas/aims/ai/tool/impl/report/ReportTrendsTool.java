package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Trends Report Tool
 *
 * Provides trend analysis reports including historical data trends,
 * forecasting, pattern recognition, and comparative analysis.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportTrendsTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_trends";
    }

    @Override
    public String getDescription() {
        return "获取趋势分析报表，包含历史数据趋势、预测分析、模式识别、同比环比对比等数据。" +
                "适用场景：趋势分析、预测决策、历史对比、周期性分析。";
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

        // metric: 指标类型（可选）
        Map<String, Object> metric = new HashMap<>();
        metric.put("type", "string");
        metric.put("description", "趋势指标类型");
        metric.put("enum", Arrays.asList("PRODUCTION", "QUALITY", "EFFICIENCY", "INVENTORY", "COST", "REVENUE", "ALL"));
        properties.put("metric", metric);

        // days: 分析天数（可选）
        Map<String, Object> days = new HashMap<>();
        days.put("type", "integer");
        days.put("description", "趋势分析的天数范围");
        days.put("default", 30);
        days.put("minimum", 7);
        days.put("maximum", 365);
        properties.put("days", days);

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
        log.info("执行趋势分析报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "month");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String metric = getString(params, "metric", "PRODUCTION");
        Integer days = getInteger(params, "days", 30);

        // 调用服务获取趋势分析报表数据
        Map<String, Object> trendsData = reportService.getTrendsDashboard(factoryId, period, metric, days);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "TRENDS");
        result.put("period", period);
        result.put("days", days);
        result.put("metric", metric);
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", trendsData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("days", days);
        queryConditions.put("metric", metric);
        if (startDateStr != null) queryConditions.put("startDate", startDateStr);
        if (endDateStr != null) queryConditions.put("endDate", endDateStr);
        result.put("queryConditions", queryConditions);

        log.info("趋势分析报表查询完成 - 工厂ID: {}, 指标: {}, 天数: {}", factoryId, metric, days);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的趋势数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "metric", "请问要分析哪个指标的趋势？可选：生产、质量、效率、库存、成本、收入、全部。",
            "days", "请问要分析多少天的趋势数据？（7-365天）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "metric", "指标类型",
            "days", "分析天数"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
