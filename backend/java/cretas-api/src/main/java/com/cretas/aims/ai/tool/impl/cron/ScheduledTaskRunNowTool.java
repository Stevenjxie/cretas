package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private ScheduledTaskRepository taskRepository;

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
        UUID taskId = parseUuid(getString(params, "taskId"));

        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404,
                        "定时任务不存在: " + taskId).withHint("请检查 taskId"));
        guardFactoryAccess(existing, factoryId);

        ScheduledTaskRunLog runLog = dynamicSchedulerService.runNow(taskId);

        String statusText = runLog.getStatus().name();
        String message;
        if (runLog.getStatus().name().equals("SUCCESS")) {
            message = String.format("手动执行成功: %s (%s) — 耗时 %d ms",
                    existing.getTaskName(), existing.getTaskCode(),
                    runLog.getDurationMs() != null ? runLog.getDurationMs() : -1);
        } else {
            String err = runLog.getErrorMsg();
            String firstLine = err != null ? err.split("\n", 2)[0] : "(no error msg)";
            message = String.format("手动执行失败: %s (%s) — %s",
                    existing.getTaskName(), existing.getTaskCode(), firstLine);
        }
        log.info("[Canvas-Cron Tool] scheduled_task_run_now: status={} taskCode={} durationMs={}",
                statusText, existing.getTaskCode(), runLog.getDurationMs());
        return buildSimpleResult(message, runLog);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            throw new BusinessException(400, "taskId 不能为空");
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "taskId 不是有效的 UUID: " + s);
        }
    }

    private static void guardFactoryAccess(ScheduledTask task, String callerFactoryId) {
        if (task.getFactoryId() == null) return;
        if (callerFactoryId == null || !task.getFactoryId().equals(callerFactoryId)) {
            throw new BusinessException(403,
                    "无权操作其它工厂的定时任务 (任务工厂=" + task.getFactoryId()
                            + ", 调用者工厂=" + callerFactoryId + ")")
                    .withHint("请联系任务所属工厂管理员处理");
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
