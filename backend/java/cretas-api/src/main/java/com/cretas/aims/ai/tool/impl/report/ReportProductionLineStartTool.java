package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 生产线启动 Tool
 *
 * 启动生产线，需要确认生产线编号和工单号。
 * 对应意图: PRODUCTION_LINE_START
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportProductionLineStartTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "report_production_line_start";
    }

    @Override
    public String getDescription() {
        return "启动生产线，需确认生产线编号和生产计划/工单号。" +
                "适用场景：生产线启动、开工记录。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productionLineId = new HashMap<>();
        productionLineId.put("type", "string");
        productionLineId.put("description", "生产线编号");
        properties.put("productionLineId", productionLineId);

        Map<String, Object> workOrderId = new HashMap<>();
        workOrderId.put("type", "string");
        workOrderId.put("description", "生产计划/工单号");
        properties.put("workOrderId", workOrderId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productionLineId", "workOrderId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productionLineId", "workOrderId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行生产线启动确认 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "NEED_CONFIRM");
        result.put("message", "确认启动生产线？请指定:\n1. 生产线编号\n2. 生产计划/工单号\n\n启动后将自动记录开工时间。");

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "productionLineId", "请提供要启动的生产线编号。",
            "workOrderId", "请提供生产计划或工单号。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "productionLineId", "生产线编号",
            "workOrderId", "工单号"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
