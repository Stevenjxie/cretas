package com.cretas.aims.ai.tool.impl.scheduling;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 排班执行（生成排班）Tool
 *
 * 执行排班需要更多参数，进入 slot filling。
 * 对应意图: SCHEDULING_EXECUTE_FOR_DATE, SCHEDULING_RUN_TOMORROW
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SchedulingExecuteTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scheduling_execute";
    }

    @Override
    public String getDescription() {
        return "执行排班生成，需要指定排班日期和产线编号。" +
                "适用场景：生成排班计划、明天排班、指定日期排班。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "排班日期，格式: yyyy-MM-dd 或 '明天'");
        properties.put("date", date);

        Map<String, Object> productionLineId = new HashMap<>();
        productionLineId.put("type", "string");
        productionLineId.put("description", "产线编号，如 Line-001");
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
        log.info("执行排班生成确认 - 工厂ID: {}", factoryId);

        // 排班执行需要更多参数，返回 slot filling 提示
        Map<String, Object> result = new HashMap<>();
        result.put("requiredFields", List.of("date", "productionLineId"));
        result.put("status", "NEED_MORE_INFO");
        result.put("message", "排班执行需要以下信息：(date) 排班日期、(productionLineId) 产线编号");
        result.put("formattedText", "请提供排班的具体信息：\n1. 排班日期（如：明天、2026-02-21）\n2. 产线编号（如：Line-001）\n\n示例：「安排明天Line-001的排班」");

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "date", "请提供排班日期（如：明天、2026-02-21）。",
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
