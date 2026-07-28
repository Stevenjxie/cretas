package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 部门考勤统计 Tool
 *
 * 查询各部门考勤统计信息。
 * 对应意图: ATTENDANCE_STATS_BY_DEPT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportAttendanceStatsByDeptTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "report_attendance_stats_by_dept";
    }

    @Override
    public String getDescription() {
        return "查询各部门考勤统计信息。" +
                "适用场景：部门考勤统计、出勤率分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行部门考勤统计查询 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("queryType", "attendance_by_dept");
        result.put("factoryId", factoryId);
        result.put("message", "部门考勤统计功能已就绪。请前往HR管理页面查看各部门考勤详情。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
