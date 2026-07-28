package com.cretas.aims.ai.tool.impl.scheduling;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 排班模式切换 Tool
 *
 * 切换排班模式：全自动、人工确认、禁用。
 * 对应意图: SCHEDULING_SET_AUTO, SCHEDULING_SET_MANUAL, SCHEDULING_SET_DISABLED
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class SchedulingModeChangeTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scheduling_mode_change";
    }

    @Override
    public String getDescription() {
        return "切换排班模式，支持全自动、人工确认、禁用三种模式。" +
                "适用场景：设置排班模式、切换自动排班、关闭自动排班。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> mode = new HashMap<>();
        mode.put("type", "string");
        mode.put("description", "排班模式: FULLY_AUTO(全自动), MANUAL_CONFIRM(人工确认), DISABLED(禁用)");
        mode.put("enum", Arrays.asList("FULLY_AUTO", "MANUAL_CONFIRM", "DISABLED"));
        properties.put("mode", mode);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("mode"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("mode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行排班模式切换 - 工厂ID: {}", factoryId);

        String mode = getString(params, "mode", "UNKNOWN");
        String modeName = switch (mode) {
            case "FULLY_AUTO" -> "全自动";
            case "MANUAL_CONFIRM" -> "人工确认";
            case "DISABLED" -> "禁用";
            default -> mode;
        };

        Map<String, Object> result = new HashMap<>();
        result.put("schedulingMode", mode);
        result.put("modeName", modeName);
        result.put("factoryId", factoryId);
        result.put("message", "排班模式已切换为: " + modeName);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("mode".equals(paramName)) {
            return "请选择排班模式：全自动(FULLY_AUTO)、人工确认(MANUAL_CONFIRM)、禁用(DISABLED)。";
        }
        return null;
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("mode".equals(paramName)) {
            return "排班模式";
        }
        return paramName;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
