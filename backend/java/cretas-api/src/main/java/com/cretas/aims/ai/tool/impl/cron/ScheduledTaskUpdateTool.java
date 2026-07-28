package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private ScheduledTaskRepository taskRepository;

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
        handlerBeanName.put("description", "新的 handler bean 名称 (可选). "
                + "调用 GET /api/mobile/scheduled-tasks/handlers 查询当前可用的 bean 名称列表.");
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
        UUID taskId = parseUuid(getString(params, "taskId"));

        // Cross-factory guard: caller can't update tasks owned by other factories.
        // Global tasks (factoryId NULL) require permission_admin (RequireRole on REST layer)
        // — for AI Tool, we check: caller must be admin to touch global, or be the owning factory.
        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404,
                        "定时任务不存在: " + taskId).withHint("请检查 taskId"));
        guardFactoryAccess(existing, factoryId);

        ScheduledTask patch = new ScheduledTask();
        if (params.containsKey("taskName")) patch.setTaskName(getString(params, "taskName"));
        if (params.containsKey("cronExpression")) patch.setCronExpression(getString(params, "cronExpression"));
        if (params.containsKey("handlerBeanName")) patch.setHandlerBeanName(getString(params, "handlerBeanName"));
        if (params.containsKey("enabled")) patch.setEnabled(getBoolean(params, "enabled"));

        ScheduledTask updated = dynamicSchedulerService.updateTask(taskId, patch);

        String message = String.format("定时任务已更新: %s (%s) — cron='%s', handler='%s', %s",
                updated.getTaskName(), updated.getTaskCode(), updated.getCronExpression(),
                updated.getHandlerBeanName(),
                Boolean.TRUE.equals(updated.getEnabled()) ? "已启用" : "未启用");
        log.info("[Canvas-Cron Tool] scheduled_task_update: {}", message);
        return buildSimpleResult(message, updated);
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
        // Global task (factoryId NULL): permission control is on REST layer (@RequireRole);
        // AI Tool reaches here through governance which already checks role — allow.
        if (task.getFactoryId() == null) return;
        // Per-factory task: caller must be same factory.
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
