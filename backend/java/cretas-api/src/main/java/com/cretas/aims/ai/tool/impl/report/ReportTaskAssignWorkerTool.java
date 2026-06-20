package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.WorkOrder;
import com.cretas.aims.service.WorkOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 任务分配工人 Tool
 *
 * 将工单分配给指定员工。
 * 对应意图: TASK_ASSIGN_WORKER, TASK_ASSIGN_BY_NAME
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportTaskAssignWorkerTool extends AbstractBusinessTool {

    @Autowired
    private WorkOrderService workOrderService;

    @Override
    public String getToolName() {
        return "report_task_assign_worker";
    }

    @Override
    public String getDescription() {
        return "将工单任务分配给指定员工。" +
                "适用场景：任务分配、工单指派、按姓名指派任务。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> workOrderId = new HashMap<>();
        workOrderId.put("type", "string");
        workOrderId.put("description", "工单ID");
        properties.put("workOrderId", workOrderId);

        Map<String, Object> assigneeId = new HashMap<>();
        assigneeId.put("type", "integer");
        assigneeId.put("description", "指派人ID");
        properties.put("assigneeId", assigneeId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("workOrderId", "assigneeId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("workOrderId", "assigneeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行任务分配 - 工厂ID: {}", factoryId);

        String workOrderId = getString(params, "workOrderId");
        Long assigneeId = getLong(params, "assigneeId");
        Long userId = getUserId(context);

        if (assigneeId == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "NEED_MORE_INFO");
            result.put("message", "请提供要指派的员工ID (assigneeId)");
            return result;
        }

        WorkOrder updated = workOrderService.assignWorkOrder(factoryId, workOrderId, assigneeId, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("workOrder", updated);
        result.put("assigneeId", assigneeId);
        result.put("message", "任务已分配。工单: " + updated.getOrderNumber() + ", 已指派给员工ID: " + assigneeId);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "workOrderId", "请提供要分配的工单ID。",
            "assigneeId", "请提供要指派的员工ID。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "workOrderId", "工单ID",
            "assigneeId", "指派人ID"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }
}
