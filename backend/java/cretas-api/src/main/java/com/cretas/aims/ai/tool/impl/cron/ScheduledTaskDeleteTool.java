package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas-Cron AI Tool: 删除定时任务 (Phase 5 skeleton).
 *
 * <p>Soft delete (sets deleted_at), so history is preserved. AbstractBusinessTool
 * derives ActionType=DELETE from name suffix _delete → governance handles
 * permission/preview accordingly.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class ScheduledTaskDeleteTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scheduled_task_delete";
    }

    @Override
    public String getDescription() {
        return "软删除一个定时任务 (deleted_at = now()). 适用场景: 客户说 '删掉那个月度库存报表任务'。"
                + "删除后不再触发, 历史 run log 仍可查询, 同名 taskCode 可被重建。";
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
        // Phase 5 sister chat: delegate to DynamicSchedulerService.deleteTask(taskId).
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: implement scheduled_task_delete via DynamicSchedulerService."
        );
    }
}
