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

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private ScheduledTaskRepository taskRepository;

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
        UUID taskId = parseUuid(getString(params, "taskId"));
        Boolean enabled = getBoolean(params, "enabled");
        if (enabled == null) {
            throw new BusinessException(400, "enabled 字段必填 (true/false)");
        }

        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404,
                        "定时任务不存在: " + taskId).withHint("请检查 taskId"));
        guardFactoryAccess(existing, factoryId);

        ScheduledTask updated = dynamicSchedulerService.toggleTask(taskId, enabled);
        String state = enabled ? "已启用" : "已禁用";
        String message = String.format("定时任务 %s (%s) %s",
                updated.getTaskName(), updated.getTaskCode(), state);
        log.info("[Canvas-Cron Tool] scheduled_task_toggle: {}", message);
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
