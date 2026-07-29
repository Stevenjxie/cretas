package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.report.OeeReportDTO;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * OEE Report Tool
 *
 * Provides OEE (Overall Equipment Effectiveness) reports including
 * availability, performance, quality rates, and equipment efficiency analysis.
 *
 * OEE = Availability × Performance × Quality
 * - Availability: Actual Run Time / Planned Production Time
 * - Performance: (Actual Output × Ideal Cycle Time) / Run Time
 * - Quality: Good Output / Total Output
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Slf4j
@Component
public class ReportOeeTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "report_oee";
    }

    @Override
    public String getDescription() {
        return "获取OEE设备综合效率报表，包含可用性、表现性、质量率、设备效率分析等数据。" +
                "适用场景：设备效率分析、生产瓶颈识别、效率优化决策。";
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

        // equipmentId: 设备ID（可选）
        Map<String, Object> equipmentId = new HashMap<>();
        equipmentId.put("type", "string");
        equipmentId.put("description", "设备ID，筛选特定设备的OEE数据");
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
        log.info("执行OEE报表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析时间参数
        String period = getString(params, "period", "week");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String equipmentId = getString(params, "equipmentId");

        // 计算日期范围
        LocalDate endDate = LocalDate.now();
        LocalDate startDate;

        if (startDateStr != null && endDateStr != null) {
            // 使用用户指定的日期范围
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } else {
            // 根据周期计算日期范围
            switch (period) {
                case "today":
                    startDate = endDate;
                    break;
                case "week":
                    startDate = endDate.minusWeeks(1);
                    break;
                case "month":
                    startDate = endDate.minusMonths(1);
                    break;
                case "quarter":
                    startDate = endDate.minusMonths(3);
                    break;
                case "year":
                    startDate = endDate.minusYears(1);
                    break;
                default:
                    startDate = endDate.minusWeeks(1);
            }
        }

        // 调用服务获取OEE报表数据
        OeeReportDTO oeeData = reportService.getOeeReport(factoryId, startDate, endDate);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "OEE");
        result.put("period", period);

        // 日期范围
        Map<String, String> dateRange = new HashMap<>();
        dateRange.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        dateRange.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        result.put("dateRange", dateRange);

        result.put("generatedAt", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        result.put("data", oeeData);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("period", period);
        queryConditions.put("startDate", startDate.format(DateTimeFormatter.ISO_DATE));
        queryConditions.put("endDate", endDate.format(DateTimeFormatter.ISO_DATE));
        if (equipmentId != null) queryConditions.put("equipmentId", equipmentId);
        result.put("queryConditions", queryConditions);

        log.info("OEE报表查询完成 - 工厂ID: {}, 日期范围: {} ~ {}", factoryId, startDate, endDate);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "period", "请问您想查看哪个时间段的OEE数据？可选：今日、本周、本月、本季度、本年。",
            "startDate", "请问报表的开始日期是？（格式：yyyy-MM-dd）",
            "endDate", "请问报表的结束日期是？（格式：yyyy-MM-dd）",
            "equipmentId", "请问要查看哪台设备的OEE？如需查看全部设备可不指定。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "period", "时间周期",
            "startDate", "开始日期",
            "endDate", "结束日期",
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
