package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas-Cron AI Tool: 手动立即执行定时任务一次 (Phase 5 skeleton).
 *
 * <p>Synchronous run on caller thread by default — sister chat may switch to
 * async returning a run-log id polled later. Returns the run log row
 * (status SUCCESS or FAILED with error_msg).
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class ScheduledTaskRunNowTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scheduled_task_run_now";
    }

    @Override
    public String getDescription() {
        return "立即触发一次定时任务 (不等下一个 cron tick). 适用场景: 客户说 '现在跑一次月度库存报表试试'。"
                + "执行结果(成功/失败 + 耗时 + 错误)立即返回。不影响后续按 cron 自动触发。";
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
    public ActionType getActionType() {
        return ActionType.GENERATE;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("taskId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // Phase 5 sister chat: delegate to DynamicSchedulerService.runNow(taskId).
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: implement scheduled_task_run_now via DynamicSchedulerService."
        );
    }
}
