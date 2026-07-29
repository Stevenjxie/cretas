package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 部门考勤查询工具
 *
 * 查询指定部门的考勤汇总情况，包括出勤率、迟到早退人数等。
 * 作为查询类Tool，无必需参数。
 *
 * Intent Code: ATTENDANCE_DEPARTMENT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AttendanceDepartmentTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "attendance_department";
    }

    @Override
    public String getDescription() {
        return "查询部门考勤汇总。获取指定部门在某一天或某段时间内的考勤统计，包括出勤人数、缺勤人数、迟到早退情况。" +
                "适用场景：查看部门今日出勤情况、部门考勤统计报表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // departmentId: 部门ID（可选，管理员可查所有部门）
        Map<String, Object> departmentId = new HashMap<>();
        departmentId.put("type", "string");
        departmentId.put("description", "部门ID，不传则查询用户所属部门或全部部门（需权限）");
        properties.put("departmentId", departmentId);

        // date: 查询日期（可选，默认今天）
        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "查询日期，格式：YYYY-MM-DD，默认今天");
        date.put("format", "date");
        properties.put("date", date);

        // startDate: 开始日期（可选，用于范围查询）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式：YYYY-MM-DD");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选，用于范围查询）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式：YYYY-MM-DD");
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
        String department = getString(params, "departmentId");
        String dateStr = getString(params, "date");
        LocalDate date = (dateStr != null && !dateStr.isEmpty())
                ? LocalDate.parse(dateStr) : LocalDate.now();

        try {
            Map<String, Object> result = timeClockService.getDepartmentAttendance(factoryId, department, date);
            log.info("查询部门考勤: factoryId={}, department={}, date={}", factoryId, department, date);
            return buildSimpleResult("查询成功", result);
        } catch (Exception e) {
            log.error("查询部门考勤失败: factoryId={}, department={}", factoryId, department, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "departmentId", "请问您要查询哪个部门的考勤？",
            "date", "请问查询哪一天的考勤？",
            "startDate", "请问从哪一天开始查询？",
            "endDate", "请问查询到哪一天为止？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "departmentId", "部门ID",
            "date", "查询日期",
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
