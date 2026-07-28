package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 考勤统计工具
 *
 * 获取考勤统计数据，包括出勤率、平均工作时长、迟到率等指标。
 * 作为查询类Tool，无必需参数。
 *
 * Intent Code: ATTENDANCE_STATS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AttendanceStatsTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "attendance_stats";
    }

    @Override
    public String getDescription() {
        return "获取考勤统计数据。查看出勤率、迟到率、平均工作时长等关键指标，支持按用户、部门、时间段统计。" +
                "适用场景：查看整体考勤情况、生成考勤报表、分析出勤趋势。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // userId: 用户ID（可选）
        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "用户ID，不传则统计全体员工");
        properties.put("userId", userId);

        // departmentId: 部门ID（可选）
        Map<String, Object> departmentId = new HashMap<>();
        departmentId.put("type", "string");
        departmentId.put("description", "部门ID，不传则统计全部部门");
        properties.put("departmentId", departmentId);

        // period: 统计周期（可选）
        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "统计周期");
        period.put("enum", Arrays.asList(
                "TODAY",      // 今日
                "WEEK",       // 本周
                "MONTH",      // 本月
                "QUARTER",    // 本季度
                "YEAR",       // 本年
                "CUSTOM"      // 自定义（需指定startDate和endDate）
        ));
        period.put("default", "MONTH");
        properties.put("period", period);

        // startDate: 开始日期（可选，period为CUSTOM时使用）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式：YYYY-MM-DD");
        startDate.put("format", "date");
        properties.put("startDate", startDate);

        // endDate: 结束日期（可选，period为CUSTOM时使用）
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
        String userIdParam = getString(params, "userId");
        Long userId = (userIdParam != null && !userIdParam.isEmpty())
                ? Long.parseLong(userIdParam) : getLong(context, "userId");

        String period = getString(params, "period", "MONTH");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");

        LocalDate now = LocalDate.now();
        LocalDate startDate;
        LocalDate endDate = now;

        if ("CUSTOM".equals(period) && startDateStr != null && !startDateStr.isEmpty()) {
            startDate = LocalDate.parse(startDateStr);
            if (endDateStr != null && !endDateStr.isEmpty()) {
                endDate = LocalDate.parse(endDateStr);
            }
        } else {
            DateRange range = calculateDateRange(period, now);
            startDate = range.start;
            endDate = range.end;
        }

        try {
            Map<String, Object> stats;
            if (userIdParam != null && !userIdParam.isEmpty()) {
                stats = timeClockService.getAttendanceStatistics(factoryId, userId, startDate, endDate);
            } else {
                stats = timeClockService.getAllEmployeesAttendanceStatistics(factoryId, startDate, endDate);
            }
            log.info("查询考勤统计: factoryId={}, period={}, userId={}", factoryId, period, userId);
            // 2026-07-24 修: "查询成功"是通用占位, 前端只见四个字无内容 (工厂 AI
            // 验收标定发现)。汇总关键数字进 message, 数据仍完整挂 data。
            return buildSimpleResult(buildAttendanceSummary(stats, startDate, endDate), stats);
        } catch (Exception e) {
            log.error("查询考勤统计失败: factoryId={}, period={}", factoryId, period, e);
            throw e;
        }
    }

    /**
     * 根据统计周期计算日期范围
     */
    private DateRange calculateDateRange(String period, LocalDate now) {
        LocalDate start;
        LocalDate end = now;

        switch (period) {
            case "TODAY":
                start = now;
                break;
            case "WEEK":
                start = now.minusDays(now.getDayOfWeek().getValue() - 1);
                break;
            case "MONTH":
                start = now.withDayOfMonth(1);
                break;
            case "QUARTER":
                int currentQuarter = (now.getMonthValue() - 1) / 3;
                start = now.withMonth(currentQuarter * 3 + 1).withDayOfMonth(1);
                break;
            case "YEAR":
                start = now.withDayOfYear(1);
                break;
            default:
                start = now.withDayOfMonth(1);
        }

        return new DateRange(start, end);
    }

    /**
     * 汇总考勤关键数字为可读 message (占位符"查询成功"修复, 2026-07-24)。
     * 字段缺失时逐项跳过 — 单人/全员两种 stats 形状都兼容。
     */
    private String buildAttendanceSummary(Map<String, Object> stats,
                                          java.time.LocalDate startDate,
                                          java.time.LocalDate endDate) {
        if (stats == null || stats.isEmpty()) {
            return "该时段没有考勤记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("考勤统计 (").append(startDate).append(" 至 ").append(endDate).append("): ");
        Object totalEmployees = stats.get("totalEmployees");
        if (totalEmployees != null) {
            sb.append("在册 ").append(totalEmployees).append(" 人, ");
        }
        Object totalRecords = stats.get("totalRecords");
        if (totalRecords != null) {
            sb.append("打卡 ").append(totalRecords).append(" 次");
        }
        Object normal = stats.get("normalCount");
        Object late = stats.get("lateCount");
        Object early = stats.get("earlyLeaveCount");
        Object absent = stats.get("absentCount");
        if (normal != null || late != null) {
            sb.append(" (正常 ").append(normal != null ? normal : 0)
              .append(", 迟到 ").append(late != null ? late : 0)
              .append(", 早退 ").append(early != null ? early : 0)
              .append(", 缺勤 ").append(absent != null ? absent : 0).append(")");
        }
        Object rate = stats.get("attendanceRate");
        if (rate instanceof Number) {
            sb.append(", 出勤率 ").append(String.format("%.1f%%", ((Number) rate).doubleValue()));
        }
        return sb.toString();
    }

    private static class DateRange {
        final LocalDate start;
        final LocalDate end;

        DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问您要查看哪位员工的考勤统计？",
            "departmentId", "请问查看哪个部门的考勤统计？",
            "period", "请问查看哪个时间段的统计？（今日/本周/本月/本季度/本年）",
            "startDate", "请问从哪一天开始统计？",
            "endDate", "请问统计到哪一天为止？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID",
            "departmentId", "部门ID",
            "period", "统计周期",
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
