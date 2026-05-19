package com.cretas.aims.ai.tool.impl.cron;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas-Cron AI Tool: 创建定时任务 (Phase 5 skeleton).
 *
 * <p>Intent: AI 用户说 "每周一早 9 点生成上周库存报表" → LLM 调本 Tool with
 * cronExpression="0 0 9 ? * MON", taskCode="weekly-inventory-report",
 * handlerBeanName="inventoryReportHandler".
 *
 * <p>Phase 5 sister chat fills {@link #doExecute} by delegating to
 * {@code DynamicSchedulerService.createTask()}.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class ScheduledTaskCreateTool extends AbstractBusinessTool {

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
        handlerBeanName.put("description", "Spring bean 名称, 必须实现 TaskHandler 接口, 如 inventoryReportHandler, echoTaskHandler");
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
        // Phase 5 sister chat: delegate to DynamicSchedulerService.createTask().
        // 1. Build ScheduledTask from params (factoryId from getString(params, "targetFactoryId"), fallback to context factoryId or null=global).
        // 2. Validate cron via CronExpression.parse() — throw on invalid.
        // 3. Validate handler bean exists & is TaskHandler.
        // 4. Save via service, which also calls dynamicScheduler.reload().
        // 5. Return buildSimpleResult(...).
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: implement scheduled_task_create via DynamicSchedulerService."
        );
    }
}
