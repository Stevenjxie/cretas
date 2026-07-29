package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 部门考勤统计工具
 *
 * 查询全部部门的考勤统计数据。
 *
 * Intent Code: ATTENDANCE_STATS_BY_DEPT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class AttendanceStatsByDeptTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "attendance_stats_by_dept";
    }

    @Override
    public String getDescription() {
        return "部门考勤统计。查询全部部门的考勤统计数据，支持按日期范围筛选。" +
                "适用场景：查看各部门出勤率、了解部门考勤情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式：YYYY-MM-DD，默认本月1号");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式：YYYY-MM-DD，默认今天");
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
        log.info("执行部门考勤统计 - 工厂ID: {}, 参数: {}", factoryId, params);

        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");

        LocalDate startDate = startDateStr != null ? LocalDate.parse(startDateStr) : LocalDate.now().withDayOfMonth(1);
        LocalDate endDate = endDateStr != null ? LocalDate.parse(endDateStr) : LocalDate.now();

        Map<String, Object> allStats = timeClockService.getAllEmployeesAttendanceStatistics(factoryId, startDate, endDate);

        return buildSimpleResult(
                "部门考勤统计 (" + startDate + " ~ " + endDate + ")",
                allStats
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "startDate", "请问统计的开始日期是？",
                "endDate", "请问统计的结束日期是？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "startDate", "开始日期",
                "endDate", "结束日期"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
