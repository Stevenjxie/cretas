package com.cretas.aims.ai.tool.impl.scheduling;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.service.SchedulingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * 排班列表查询 Tool
 *
 * 查询排班计划列表。
 * 对应意图: SCHEDULING_LIST, SCHEDULING_QUERY, SCHEDULING_COVERAGE_QUERY, SCHEDULING_QUERY_COVERAGE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SchedulingListTool extends AbstractBusinessTool {

    @Autowired
    private SchedulingService schedulingService;

    @Override
    public String getToolName() {
        return "scheduling_list";
    }

    @Override
    public String getDescription() {
        return "查询排班计划列表，支持按日期范围筛选。" +
                "适用场景：排班查询、排班覆盖率查询、排班计划列表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "日期或时间范围关键词: today, this_week, this_month, 或具体日期 yyyy-MM-dd");
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
        log.info("执行排班列表查询 - 工厂ID: {}", factoryId);

        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().plusDays(7);

        String dateStr = getString(params, "date");
        if (dateStr != null) {
            if (dateStr.equals("今天") || dateStr.equals("today")) {
                startDate = LocalDate.now();
                endDate = startDate.plusDays(1);
            } else if (dateStr.equals("本周") || dateStr.equals("this_week")) {
                startDate = LocalDate.now().with(DayOfWeek.MONDAY);
                endDate = startDate.plusDays(7);
            } else if (dateStr.equals("本月") || dateStr.equals("this_month")) {
                startDate = LocalDate.now().withDayOfMonth(1);
                endDate = startDate.plusMonths(1);
            } else {
                try {
                    startDate = LocalDate.parse(dateStr);
                    endDate = startDate.plusDays(1);
                } catch (Exception e) {
                    log.debug("无法解析日期 '{}', 使用默认范围", dateStr);
                }
            }
        }

        Page<SchedulingPlanDTO> plans = schedulingService.getPlans(
                factoryId, startDate, endDate, null, PageRequest.of(0, 10));

        Map<String, Object> result = new HashMap<>();
        result.put("plans", plans.getContent());
        result.put("total", plans.getTotalElements());
        result.put("period", startDate + " 至 " + endDate);

        StringBuilder sb = new StringBuilder();
        sb.append("排班计划（").append(startDate).append(" ~ ").append(endDate).append("）\n");
        if (plans.isEmpty()) {
            sb.append("当前时段暂无排班计划");
        } else {
            sb.append("共 ").append(plans.getTotalElements()).append(" 个计划：\n");
            int i = 1;
            for (SchedulingPlanDTO plan : plans.getContent()) {
                sb.append(i++).append(". ")
                  .append(plan.getPlanName() != null ? plan.getPlanName() : "未编号")
                  .append(" | 状态: ").append(plan.getStatus() != null ? plan.getStatus() : "未知")
                  .append("\n");
                if (i > 5) break;
            }
            if (plans.getTotalElements() > 5) {
                sb.append("... 等共 ").append(plans.getTotalElements()).append(" 个计划");
            }
        }

        result.put("message", sb.toString());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("date".equals(paramName)) {
            return "请问要查看哪个时间段的排班？可选：今天、本周、本月，或指定日期。";
        }
        return null;
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("date".equals(paramName)) {
            return "日期";
        }
        return paramName;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
