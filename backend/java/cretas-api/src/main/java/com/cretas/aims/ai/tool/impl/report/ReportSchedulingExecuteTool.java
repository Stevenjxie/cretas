package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.SchedulingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 排班执行（生成排班）Tool
 *
 * 处理排班执行请求，需要用户提供排班日期和产线编号。
 * 对应意图: SCHEDULING_EXECUTE_FOR_DATE, SCHEDULING_RUN_TOMORROW
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class ReportSchedulingExecuteTool extends AbstractBusinessTool {

    @Autowired
    private SchedulingService schedulingService;

    @Override
    public String getToolName() {
        return "report_scheduling_execute";
    }

    @Override
    public String getDescription() {
        return "执行排班生成，需要指定排班日期和产线编号。" +
                "适用场景：生成排班计划、安排明天排班、按日期执行排班。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "排班日期，格式: yyyy-MM-dd，或 '明天'/'tomorrow'");
        properties.put("date", date);

        Map<String, Object> productionLineId = new HashMap<>();
        productionLineId.put("type", "string");
        productionLineId.put("description", "产线编号，如: Line-001");
        properties.put("productionLineId", productionLineId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("date", "productionLineId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("date", "productionLineId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行排班生成 - 工厂ID: {}, 参数: {}", factoryId, params);

        String date = getString(params, "date");
        String productionLineId = getString(params, "productionLineId");

        // 排班执行需要用户确认，返回确认信息
        Map<String, Object> result = new HashMap<>();
        result.put("requiredFields", List.of("date", "productionLineId"));
        result.put("date", date);
        result.put("productionLineId", productionLineId);
        result.put("status", "NEED_CONFIRM");
        result.put("message", "排班执行需要确认。排班日期: " + date + ", 产线: " + productionLineId +
                "。请确认后执行。\n\n提示: 排班将根据当前生产计划和人员配置自动生成。");

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "date", "请提供排班日期（如：明天、2026-03-09）。",
            "productionLineId", "请提供产线编号（如：Line-001）。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "date", "排班日期",
            "productionLineId", "产线编号"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
