package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 考勤历史查询工具
 *
 * 查询指定用户的历史考勤记录，支持日期范围筛选和分页。
 * 作为查询类Tool，无必需参数。
 *
 * Intent Code: ATTENDANCE_HISTORY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AttendanceHistoryTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "attendance_history";
    }

    @Override
    public String getDescription() {
        return "查询考勤历史记录。获取指定用户在一段时间内的考勤详情，支持分页。" +
                "适用场景：查看过去一周/一个月的考勤、查看某人的考勤记录。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // userId: 用户ID（可选，默认当前用户）
        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "用户ID，不传则查询当前登录用户");
        properties.put("userId", userId);

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
        // Use userId from params if provided, otherwise fall back to context (current user)
        Long userId;
        String userIdParam = getString(params, "userId");
        if (userIdParam != null && !userIdParam.isEmpty()) {
            userId = Long.parseLong(userIdParam);
        } else {
            userId = getLong(context, "userId");
        }

        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        LocalDate startDate = (startDateStr != null && !startDateStr.isEmpty())
                ? LocalDate.parse(startDateStr) : LocalDate.now().minusDays(30);
        LocalDate endDate = (endDateStr != null && !endDateStr.isEmpty())
                ? LocalDate.parse(endDateStr) : LocalDate.now();

        int page = getInteger(params, "page", 1);
        int size = getInteger(params, "size", 10);
        PageRequest pageRequest = PageRequest.of(page, size);

        try {
            PageResponse<TimeClockRecord> response = timeClockService.getClockHistory(
                    factoryId, userId, startDate, endDate, pageRequest);
            log.info("查询考勤历史: factoryId={}, userId={}, startDate={}, endDate={}", factoryId, userId, startDate, endDate);
            return buildSimpleResult("查询成功", response);
        } catch (Exception e) {
            log.error("查询考勤历史失败: factoryId={}, userId={}", factoryId, userId, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问您要查询哪位员工的考勤历史？",
            "startDate", "请问从哪一天开始查询？",
            "endDate", "请问查询到哪一天为止？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID",
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
