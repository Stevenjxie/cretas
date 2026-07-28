package com.cretas.aims.ai.tool.impl.report;

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
 * 查询排班计划列表，支持按日期范围筛选。
 * 对应意图: SCHEDULING_LIST, SCHEDULING_QUERY, SCHEDULING_COVERAGE_QUERY, SCHEDULING_QUERY_COVERAGE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class ReportSchedulingListTool extends AbstractBusinessTool {

    @Autowired
    private SchedulingService schedulingService;

    @Override
    public String getToolName() {
        return "report_scheduling_list";
    }

    @Override
    public String getDescription() {
        return "查询排班计划列表，支持按日期范围和时间周期筛选。" +
                "适用场景：查看排班计划、排班覆盖率查询、本周排班、今日排班。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "日期或时间范围: 今天/today, 本周/this_week, 本月/this_month，或具体日期 yyyy-MM-dd");
        date.put("default", "this_week");
        properties.put("date", date);

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从0开始，默认0");
        page.put("default", 0);
        properties.put("page", page);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页数量，默认10");
        size.put("default", 10);
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
        log.info("执行排班列表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String dateStr = getString(params, "date", "this_week");
        Integer pageNum = getInteger(params, "page", 0);
        Integer pageSize = getInteger(params, "size", 10);

        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().plusDays(7);

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
                log.debug("无法解析日期 '{}', 使用默认范围(本周)", dateStr);
            }
        }

        Page<SchedulingPlanDTO> plans = schedulingService.getPlans(
                factoryId, startDate, endDate, null, PageRequest.of(pageNum, pageSize));

        Map<String, Object> result = new HashMap<>();
        result.put("plans", plans.getContent());
        result.put("total", plans.getTotalElements());
        result.put("totalPages", plans.getTotalPages());
        result.put("currentPage", pageNum);
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

        result.put("message", sb.toString().trim());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "date", "请问您要查看哪个时段的排班？可选：今天、本周、本月，或具体日期（yyyy-MM-dd）。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "date", "查询日期/时段",
            "page", "页码",
            "size", "每页数量"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
