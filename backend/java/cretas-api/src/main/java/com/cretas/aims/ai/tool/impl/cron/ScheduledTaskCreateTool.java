package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas-Cron AI Tool: 创建定时任务 (Phase 5 skeleton).
 *
 * <p>Intent: AI 用户说 "每周一早 9 点生成上周库存报表" → LLM 调本 Tool with
 * cronExpression="0 0 9 ? * MON", taskCode="weekly-inventory-report",
 * handlerBeanName=<bean name returned by GET /api/mobile/scheduled-tasks/handlers>.
 *
 * <p>Phase 5 sister chat fills {@link #doExecute} by delegating to
 * {@code DynamicSchedulerService.createTask()}.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class ScheduledTaskCreateTool extends AbstractBusinessTool {

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Override
    public String getToolName() {
        return "scheduled_task_create";
    }

    @Override
    public String getDescription() {
        return "创建一个定时任务 (DB-driven cron). 适用场景: 客户说"
                + "'每周一早 9 点生成上周库存报表'、'每月 1 号触发应收催收' 等。"
                + "需提供 cron 表达式 (6 字段: 秒 分 时 日 月 周)、handler bean 名称、任务代码。"
                + "factoryId 为 null 表示全工厂任务, 非 null 表示仅该工厂触发。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> taskCode = new HashMap<>();
        taskCode.put("type", "string");
        taskCode.put("description", "任务代码 (英文蛇形或短横线), 全局或 per-factory 唯一, 如 weekly-inventory-report");
        properties.put("taskCode", taskCode);

        Map<String, Object> taskName = new HashMap<>();
        taskName.put("type", "string");
        taskName.put("description", "任务人类可读名称, 如 每周库存报表");
        properties.put("taskName", taskName);

        Map<String, Object> cronExpression = new HashMap<>();
        cronExpression.put("type", "string");
        cronExpression.put("description", "Spring cron 表达式 (6 字段: 秒 分 时 日 月 周), 如 '0 0 9 ? * MON' = 每周一 09:00");
        properties.put("cronExpression", cronExpression);

        Map<String, Object> handlerBeanName = new HashMap<>();
        handlerBeanName.put("type", "string");
        handlerBeanName.put("description", "Spring bean 名称, 必须实现 TaskHandler 接口. "
                + "调用 GET /api/mobile/scheduled-tasks/handlers 查询当前可用的 bean 名称列表 (默认包含 echoTaskHandler).");
        properties.put("handlerBeanName", handlerBeanName);

        Map<String, Object> targetFactoryId = new HashMap<>();
        targetFactoryId.put("type", "string");
        targetFactoryId.put("description", "目标工厂 ID, 留空 (null) 表示全工厂任务");
        properties.put("targetFactoryId", targetFactoryId);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "是否立即启用, 默认 true");
        properties.put("enabled", enabled);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("taskCode", "cronExpression", "handlerBeanName"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("taskCode", "cronExpression", "handlerBeanName");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String taskCode = getString(params, "taskCode");
        String taskName = getString(params, "taskName", taskCode);
        String cronExpression = getString(params, "cronExpression");
        String handlerBeanName = getString(params, "handlerBeanName");
        Boolean enabled = getBoolean(params, "enabled", Boolean.TRUE);

        // targetFactoryId: optional param. Empty string → global. Missing → fall back to
        // caller's factoryId from context (per-factory). Explicit "null" / "global" → null=global.
        String targetFactoryId = getString(params, "targetFactoryId");
        String resolvedFactoryId;
        if (targetFactoryId == null) {
            resolvedFactoryId = factoryId;  // default to caller's scope
        } else if (targetFactoryId.isBlank()
                || "null".equalsIgnoreCase(targetFactoryId)
                || "global".equalsIgnoreCase(targetFactoryId)) {
            resolvedFactoryId = null;       // explicit global
        } else {
            resolvedFactoryId = targetFactoryId;
        }

        ScheduledTask task = ScheduledTask.builder()
                .taskCode(taskCode)
                .taskName(taskName)
                .cronExpression(cronExpression)
                .handlerBeanName(handlerBeanName)
                .factoryId(resolvedFactoryId)
                .enabled(enabled)
                .build();

        ScheduledTask saved = dynamicSchedulerService.createTask(task);

        String scopeText = resolvedFactoryId == null ? "全工厂" : ("工厂 " + resolvedFactoryId);
        String message = String.format("定时任务已创建: %s (%s) — cron='%s', handler='%s', %s, %s",
                saved.getTaskName(), saved.getTaskCode(), saved.getCronExpression(),
                saved.getHandlerBeanName(), scopeText,
                Boolean.TRUE.equals(saved.getEnabled()) ? "已启用" : "未启用");
        log.info("[Canvas-Cron Tool] scheduled_task_create: {}", message);
        return buildSimpleResult(message, saved);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
