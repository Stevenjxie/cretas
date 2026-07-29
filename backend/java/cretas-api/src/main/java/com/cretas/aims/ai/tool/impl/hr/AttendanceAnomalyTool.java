package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.TimeStatsDTO;
import com.cretas.aims.service.TimeStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 考勤异常查询工具
 *
 * 查询考勤异常记录，如迟到、早退、缺勤、漏打卡等。
 * 作为查询类Tool，无必需参数。
 *
 * Intent Code: ATTENDANCE_ANOMALY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AttendanceAnomalyTool extends AbstractBusinessTool {

    @Autowired
    private TimeStatsService timeStatsService;

    @Override
    public String getToolName() {
        return "attendance_anomaly";
    }

    @Override
    public String getDescription() {
        return "查询考勤异常记录。获取迟到、早退、缺勤、漏打卡等异常情况列表，支持按用户、部门、日期范围筛选。" +
                "适用场景：查看今日迟到人员、本周缺勤记录、查找漏打卡员工。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // userId: 用户ID（可选）
        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "用户ID，不传则查询全部用户");
        properties.put("userId", userId);

        // departmentId: 部门ID（可选）
        Map<String, Object> departmentId = new HashMap<>();
        departmentId.put("type", "string");
        departmentId.put("description", "部门ID，不传则查询全部部门");
        properties.put("departmentId", departmentId);

        // anomalyType: 异常类型（可选）
        Map<String, Object> anomalyType = new HashMap<>();
        anomalyType.put("type", "string");
        anomalyType.put("description", "异常类型筛选");
        anomalyType.put("enum", Arrays.asList(
                "LATE",           // 迟到
                "EARLY_LEAVE",    // 早退
                "ABSENT",         // 缺勤
                "MISSING_CLOCK_IN",   // 漏签到
                "MISSING_CLOCK_OUT",  // 漏签退
                "OVERTIME"        // 超时工作
        ));
        properties.put("anomalyType", anomalyType);

        // startDate: 开始日期（可选）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式：YYYY-MM-DD");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式：YYYY-MM-DD");
        endDate.put("format", "date");
        properties.put("endDate", endDate);

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

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
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        LocalDate startDate = (startDateStr != null && !startDateStr.isEmpty())
                ? LocalDate.parse(startDateStr) : LocalDate.now().minusDays(30);
        LocalDate endDate = (endDateStr != null && !endDateStr.isEmpty())
                ? LocalDate.parse(endDateStr) : LocalDate.now();

        try {
            TimeStatsDTO stats = timeStatsService.getAnomalyStats(factoryId, startDate, endDate);
            log.info("查询考勤异常: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
            return buildSimpleResult("查询成功", stats);
        } catch (Exception e) {
            log.error("查询考勤异常失败: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问您要查询哪位员工的异常记录？",
            "departmentId", "请问查询哪个部门的异常记录？",
            "anomalyType", "请问要查询哪种异常类型？（迟到/早退/缺勤/漏打卡）",
            "startDate", "请问从哪一天开始查询？",
            "endDate", "请问查询到哪一天为止？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID",
            "departmentId", "部门ID",
            "anomalyType", "异常类型",
            "startDate", "开始日期",
            "endDate", "结束日期",
            "page", "页码",
            "size", "每页数量"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
