package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 考勤状态查询工具
 *
 * 查询指定用户的当前考勤状态（是否在岗、是否迟到早退等）。
 * 作为查询类Tool，无必需参数。
 *
 * Intent Code: ATTENDANCE_STATUS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AttendanceStatusTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "attendance_status";
    }

    @Override
    public String getDescription() {
        return "查询考勤状态。获取指定用户的实时考勤状态，包括是否已签到、是否在岗、迟到早退情况。" +
                "适用场景：查看某人是否在岗、查看自己的考勤状态。";
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

        // date: 日期（可选，默认今天）
        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "查询日期，格式：YYYY-MM-DD，默认今天");
        date.put("format", "date");
        properties.put("date", date);

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

        try {
            Map<String, Object> status = timeClockService.getClockStatus(factoryId, userId);
            log.info("查询考勤状态: factoryId={}, userId={}", factoryId, userId);
            return buildSimpleResult("查询成功", status);
        } catch (Exception e) {
            log.error("查询考勤状态失败: factoryId={}, userId={}", factoryId, userId, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问您要查询哪位员工的考勤状态？",
            "date", "请问您要查询哪一天的考勤状态？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID",
            "date", "查询日期"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
