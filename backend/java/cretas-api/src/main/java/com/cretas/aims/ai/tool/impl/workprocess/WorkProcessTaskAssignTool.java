package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分配工序责任人 Tool.
 *
 * <p>触发: "把工序 12 分配给小张 (user 88)" / "工序任务 12 指派给 88"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessTaskAssignTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "work_process_task_assign";
    }

    @Override
    public String getDescription() {
        return "为工序任务分配责任人。需要 taskId 和 assignedTo (userId)。不改变任务状态。"
                + "适用场景: 主管派工, 或开工前指派操作员。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("taskId", Map.of(
                "type", "integer",
                "description", "工序任务ID (Long)"));
        properties.put("assignedTo", Map.of(
                "type", "integer",
                "description", "责任人用户ID (Long)"));
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("taskId", "assignedTo"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("taskId", "assignedTo");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Long taskId = getLong(params, "taskId");
        Long assignedTo = getLong(params, "assignedTo");

        WorkProcessTaskDTO.UpdatePlanRequest req = WorkProcessTaskDTO.UpdatePlanRequest.builder()
                .assignedTo(assignedTo)
                .build();
        WorkProcessTaskDTO result = workProcessTaskService.updatePlan(factoryId, taskId, req);
        log.info("AI 工具分配工序 task={}, assignedTo={}", taskId, assignedTo);
        return buildSimpleResult(
                String.format("工序任务 %d 已分配给用户 %d", taskId, assignedTo),
                result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
