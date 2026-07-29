package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 在线员工数量查询工具
 *
 * 查询今日已打卡（在岗）员工数量。
 *
 * Intent Code: QUERY_ONLINE_STAFF_COUNT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QueryOnlineStaffCountTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "query_online_staff_count";
    }

    @Override
    public String getDescription() {
        return "查询在线员工数量。获取今日已打卡的在岗员工人数和总员工人数。" +
                "适用场景：查看当前在岗人数、了解出勤情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行在线员工数量查询 - 工厂ID: {}", factoryId);

        LocalDate today = LocalDate.now();
        Map<String, Object> stats = timeClockService.getAllEmployeesAttendanceStatistics(factoryId, today, today);

        List<Map<String, Object>> employees = (List<Map<String, Object>>) stats.getOrDefault("employees", List.of());
        long onlineCount = employees.stream()
                .filter(e -> (int) e.getOrDefault("presentDays", 0) > 0)
                .count();

        Map<String, Object> result = new HashMap<>();
        result.put("onlineCount", onlineCount);
        result.put("totalEmployees", employees.size());
        result.put("date", today);

        return buildSimpleResult(
                "今日在岗员工 " + onlineCount + " 人，共 " + employees.size() + " 名员工",
                result
        );
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
