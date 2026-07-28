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
 * Dashboard Overview Report Tool
 *
 * Provides comprehensive dashboard overview data including key metrics,
 * production summary, quality overview, and inventory status.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportDashboardOverviewTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_dashboard_overview";
    }

    @Override
    public String getDescription() {
        return "获取仪表盘概览报表，包含关键指标、生产概况、质量概况、库存状态等综合数据。" +
                "适用场景：管理层总览、每日经营概况、实时监控、快速了解工厂整体运营状态。";
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
        period.put("default", "today");
        properties.put("period", period);

        // startDate: 开始日期（可选）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd，如指定则覆盖period参数");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd，如指定则覆盖period参数");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

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
        log.info("执行Dashboard概览报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "today");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");

        // 如果指定了日期范围，则使用自定义日期
        if (startDateStr != null && endDateStr != null) {
            period = "custom";
        }

        // 调用服务获取Dashboard概览数据
        Map<String, Object> dashboardData = reportService.getDashboardOverview(factoryId, period);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "DASHBOARD_OVERVIEW");
        result.put("period", period);
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", dashboardData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        if (startDateStr != null) queryConditions.put("startDate", startDateStr);
        if (endDateStr != null) queryConditions.put("endDate", endDateStr);
        result.put("queryConditions", queryConditions);

        log.info("Dashboard概览报表查询完成 - 工厂ID: {}", factoryId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
