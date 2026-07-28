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
 * Efficiency Report Tool
 *
 * Provides efficiency-related reports including equipment utilization,
 * production efficiency, labor productivity, and resource optimization.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ReportEfficiencyTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_efficiency";
    }

    @Override
    public String getDescription() {
        return "获取效率报表，包含设备利用率、生产效率、劳动生产率、资源优化分析等数据。" +
                "适用场景：效率分析、产能利用、设备OEE分析、人均产出评估。";
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
        period.put("default", "week");
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

        // efficiencyType: 效率类型（可选）
        Map<String, Object> efficiencyType = new HashMap<>();
        efficiencyType.put("type", "string");
        efficiencyType.put("description", "效率类型筛选");
        efficiencyType.put("enum", Arrays.asList("EQUIPMENT", "LABOR", "PRODUCTION_LINE", "OVERALL", "ALL"));
        properties.put("efficiencyType", efficiencyType);

        // equipmentId: 设备ID（可选）
        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "string");
        equipmentId.put("description", "设备ID，筛选特定设备的效率数据");
        properties.put("equipmentId", equipmentId);

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
        log.info("执行效率报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "week");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String efficiencyType = getString(params, "efficiencyType");
        String equipmentId = getString(params, "equipmentId");

        // 计算日期范围
        LocalDate[] dateRange = calculateDateRange(period, startDateStr, endDateStr);
        LocalDate startDate = dateRange[0];
        LocalDate endDate = dateRange[1];

        // 调用服务获取效率报表数据
        Map<String, Object> efficiencyData = reportService.getEfficiencyAnalysisReport(factoryId, startDate, endDate);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "EFFICIENCY");
        result.put("period", period);
        result.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        result.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", efficiencyData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        queryConditions.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        if (efficiencyType != null) queryConditions.put("efficiencyType", efficiencyType);
        if (equipmentId != null) queryConditions.put("equipmentId", equipmentId);
        result.put("queryConditions", queryConditions);

        log.info("效率报表查询完成 - 工厂ID: {}, 日期范围: {} ~ {}", factoryId, startDate, endDate);

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
                    startDate = LocalDate.now().minusWeeks(1);
            }
        }

        return new LocalDate[]{startDate, endDate};
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的效率数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "efficiencyType", "请问要查看哪种效率类型？可选：设备效率、劳动效率、产线效率、综合效率、全部。",
            "equipmentId", "请问要查看哪台设备的效率？如需全部设备可不指定。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "efficiencyType", "效率类型",
            "equipmentId", "设备ID"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
