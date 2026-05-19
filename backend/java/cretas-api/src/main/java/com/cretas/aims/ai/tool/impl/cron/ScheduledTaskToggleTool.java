package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas-Cron AI Tool: 启用/禁用定时任务 (Phase 5 skeleton).
 *
 * <p>Note tool name uses {@code _toggle} suffix (not _create/_update/_delete),
 * so ActionType convention falls back to READ. Sister chat may override
 * {@link #getActionType()} to UPDATE if AI governance prefers it.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class ScheduledTaskToggleTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scheduled_task_toggle";
    }

    @Override
    public String getDescription() {
        return "启用或禁用一个定时任务. 适用场景: 客户说 '暂停每月库存报表'、'重新启用应收催收' 等。"
                + "禁用后任务不再按 cron 触发, 但 DB 行保留, 历史日志可查。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> taskId = new HashMap<>();
        taskId.put("type", "string");
        taskId.put("description", "任务 UUID");
        properties.put("taskId", taskId);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "true=启用, false=禁用");
        properties.put("enabled", enabled);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("taskId", "enabled"));

        return schema;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.UPDATE;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("taskId", "enabled");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // Phase 5 sister chat: delegate to DynamicSchedulerService.toggleTask(taskId, enabled).
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: implement scheduled_task_toggle via DynamicSchedulerService."
        );
    }
}
