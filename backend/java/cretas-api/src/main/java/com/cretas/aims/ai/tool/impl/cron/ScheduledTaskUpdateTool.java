package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas-Cron AI Tool: 修改定时任务 (Phase 5 skeleton).
 *
 * <p>Intent: "把月度库存报表改成 09:30 触发" → LLM 调本 Tool with
 * taskId + cronExpression="0 30 9 1 * ?".
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class ScheduledTaskUpdateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scheduled_task_update";
    }

    @Override
    public String getDescription() {
        return "修改定时任务的 cron 表达式 / 任务名 / handler / 启用状态. 适用场景: 客户说 "
                + "'把月度库存报表改到 09:30'、'换成新的 handler' 等。修改后立即重新加载, 不需要重启。";
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

        Map<String, Object> taskName = new HashMap<>();
        taskName.put("type", "string");
        taskName.put("description", "新的任务名称 (可选)");
        properties.put("taskName", taskName);

        Map<String, Object> cronExpression = new HashMap<>();
        cronExpression.put("type", "string");
        cronExpression.put("description", "新的 cron 表达式 (可选)");
        properties.put("cronExpression", cronExpression);

        Map<String, Object> handlerBeanName = new HashMap<>();
        handlerBeanName.put("type", "string");
        handlerBeanName.put("description", "新的 handler bean 名称 (可选)");
        properties.put("handlerBeanName", handlerBeanName);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "启用状态 (可选)");
        properties.put("enabled", enabled);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("taskId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("taskId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // Phase 5 sister chat: delegate to DynamicSchedulerService.updateTask(taskId, patch).
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: implement scheduled_task_update via DynamicSchedulerService."
        );
    }
}
