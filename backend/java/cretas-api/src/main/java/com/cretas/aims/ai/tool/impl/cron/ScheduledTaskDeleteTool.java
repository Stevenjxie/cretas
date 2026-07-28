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

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private ScheduledTaskRepository taskRepository;

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
        UUID taskId = parseUuid(getString(params, "taskId"));

        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404,
                        "定时任务不存在: " + taskId).withHint("请检查 taskId 或确认未被删除"));
        guardFactoryAccess(existing, factoryId);

        String taskCode = existing.getTaskCode();
        String taskName = existing.getTaskName();
        dynamicSchedulerService.deleteTask(taskId);

        String message = String.format("定时任务已删除: %s (%s)",
                taskName != null ? taskName : "(未命名)", taskCode);
        log.info("[Canvas-Cron Tool] scheduled_task_delete: {}", message);
        Map<String, Object> deleted = new HashMap<>();
        deleted.put("taskId", taskId);
        deleted.put("taskCode", taskCode);
        deleted.put("taskName", taskName);
        return buildSimpleResult(message, deleted);
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
                    "无权删除其它工厂的定时任务 (任务工厂=" + task.getFactoryId()
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
