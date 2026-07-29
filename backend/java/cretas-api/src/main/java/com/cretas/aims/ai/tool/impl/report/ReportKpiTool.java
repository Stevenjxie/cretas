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
 * KPI Report Tool
 *
 * Provides KPI-related reports including key performance indicators,
 * target achievement rates, performance comparisons, and scorecards.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportKpiTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_kpi";
    }

    @Override
    public String getDescription() {
        return "获取KPI报表，包含关键绩效指标、目标完成率、绩效对比、记分卡等数据。" +
                "适用场景：绩效评估、目标跟踪、KPI仪表盘、管理决策支持。";
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

        // kpiCategory: KPI类别（可选）
        Map<String, Object> kpiCategory = new HashMap<>();
        kpiCategory.put("type", "string");
        kpiCategory.put("description", "KPI类别筛选");
        kpiCategory.put("enum", Arrays.asList("PRODUCTION", "QUALITY", "EFFICIENCY", "FINANCE", "SAFETY", "ALL"));
        properties.put("kpiCategory", kpiCategory);

        // departmentId: 部门ID（可选）
        Map<String, Object> departmentId = new HashMap<>();
        departmentId.put("type", "string");
        departmentId.put("description", "部门ID，筛选特定部门的KPI数据");
        properties.put("departmentId", departmentId);

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
        log.info("执行KPI报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "month");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String kpiCategory = getString(params, "kpiCategory");
        String departmentId = getString(params, "departmentId");

        // 计算报表日期
        LocalDate reportDate = LocalDate.now();
        if (endDateStr != null) {
            reportDate = LocalDate.parse(endDateStr);
        }

        // 调用服务获取KPI报表数据
        Map<String, Object> kpiData = reportService.getKPIMetrics(factoryId, reportDate);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "KPI");
        result.put("period", period);
        result.put("reportDate", reportDate.format(DateTimeFormatter.ISO_DATE));
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", kpiData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("reportDate", reportDate.format(DateTimeFormatter.ISO_DATE));
        if (kpiCategory != null) queryConditions.put("kpiCategory", kpiCategory);
        if (departmentId != null) queryConditions.put("departmentId", departmentId);
        result.put("queryConditions", queryConditions);

        log.info("KPI报表查询完成 - 工厂ID: {}, 报表日期: {}", factoryId, reportDate);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的KPI数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "kpiCategory", "请问要查看哪类KPI？可选：生产、质量、效率、财务、安全、全部。",
            "departmentId", "请问要查看哪个部门的KPI？如需全部部门可不指定。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "kpiCategory", "KPI类别",
            "departmentId", "部门ID"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
