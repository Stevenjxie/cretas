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
 * Finance Report Tool
 *
 * Provides finance-related reports including cost analysis,
 * revenue statistics, profit margins, and financial trends.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportFinanceTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_finance";
    }

    @Override
    public String getDescription() {
        return "获取财务报表，包含成本分析、收入统计、利润率、财务趋势等数据。" +
                "适用场景：成本核算、利润分析、预算对比、财务决策支持。";
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

        // financeType: 财务类型（可选）
        Map<String, Object> financeType = new HashMap<>();
        financeType.put("type", "string");
        financeType.put("description", "财务类型筛选");
        financeType.put("enum", Arrays.asList("COST", "REVENUE", "PROFIT", "BUDGET", "ALL"));
        properties.put("financeType", financeType);

        // costCenter: 成本中心（可选）
        Map<String, Object> costCenter = new HashMap<>();
        costCenter.put("type", "string");
        costCenter.put("description", "成本中心ID，筛选特定成本中心的数据");
        properties.put("costCenter", costCenter);

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
        log.info("执行财务报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "month");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String financeType = getString(params, "financeType");
        String costCenter = getString(params, "costCenter");

        // 计算日期范围
        LocalDate[] dateRange = calculateDateRange(period, startDateStr, endDateStr);
        LocalDate startDate = dateRange[0];
        LocalDate endDate = dateRange[1];

        // 调用服务获取财务报表数据
        Map<String, Object> financeData = reportService.getFinanceReport(factoryId, startDate, endDate);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "FINANCE");
        result.put("period", period);
        result.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        result.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", financeData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        queryConditions.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        if (financeType != null) queryConditions.put("financeType", financeType);
        if (costCenter != null) queryConditions.put("costCenter", costCenter);
        result.put("queryConditions", queryConditions);

        log.info("财务报表查询完成 - 工厂ID: {}, 日期范围: {} ~ {}", factoryId, startDate, endDate);

        return result;
    }

    /**
     * 根据period参数计算日期范围
     */
    private LocalDate[] calculateDateRange(String period, String startDateStr, String endDateStr) {
        LocalDate startDate;
        LocalDate endDate = LocalDate.now();

        if (startDateStr != null && endDateStr != null) {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } else {
            switch (period) {
                case "today":
                    startDate = LocalDate.now();
                    break;
                case "week":
                    startDate = LocalDate.now().minusWeeks(1);
                    break;
                case "month":
                    startDate = LocalDate.now().minusMonths(1);
                    break;
                case "quarter":
                    startDate = LocalDate.now().minusMonths(3);
                    break;
                case "year":
                    startDate = LocalDate.now().minusYears(1);
                    break;
                default:
                    startDate = LocalDate.now().minusMonths(1);
            }
        }

        return new LocalDate[]{startDate, endDate};
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的财务数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "financeType", "请问要查看哪种财务类型？可选：成本、收入、利润、预算、全部。",
            "costCenter", "请问要查看哪个成本中心的数据？如需全部可不指定。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "financeType", "财务类型",
            "costCenter", "成本中心"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
