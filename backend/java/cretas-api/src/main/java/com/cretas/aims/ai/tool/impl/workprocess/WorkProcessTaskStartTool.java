package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开始工序 Tool: PENDING → IN_PROGRESS.
 *
 * <p>触发: "开始工序任务 12" / "执行工序 12"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessTaskStartTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "work_process_task_start";
    }

    @Override
    public String getDescription() {
        return "开始一个待开始的工序任务 (PENDING → IN_PROGRESS), 记录实际开始时间。需要 taskId。"
                + "适用场景: 责任人开工前确认开始, AI 流程触发自动开始。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("taskId", Map.of(
                "type", "integer",
                "description", "工序任务ID (Long)"));
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("taskId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("taskId");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Long taskId = getLong(params, "taskId");
        Long operatorId = SecurityUtils.getCurrentUserId();
        WorkProcessTaskDTO result = workProcessTaskService.start(factoryId, taskId, operatorId);
        log.info("AI 工具开始工序 task={}, operator={}", taskId, operatorId);
        return buildSimpleResult(
                String.format("工序任务 %d 已开始 (状态 → IN_PROGRESS)", taskId),
                result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
