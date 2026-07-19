package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessTaskQueryTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "process_task_query";
    }

    @Override
    public String getDescription() {
        return "查询生产批次的工序任务实例，支持按状态和产品筛选。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("status", Map.of(
                "type", "string",
                "description", "任务状态: PENDING/IN_PROGRESS/COMPLETED/SKIPPED/CANCELLED",
                "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")));
        properties.put("productTypeId", Map.of("type", "string", "description", "产品类型ID筛选"));
        return Map.of("type", "object", "properties", properties, "required", Collections.emptyList());
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        String rawStatus = getString(params, "status");
        String productTypeId = getString(params, "productTypeId");
        WorkProcessTask.Status status = rawStatus == null ? null : WorkProcessTask.Status.valueOf(rawStatus);
        List<WorkProcessTaskDTO> tasks = workProcessTaskService
                .list(factoryId, status, null, null, PageRequest.of(0, 200))
                .getContent().stream()
                .filter(task -> productTypeId == null || productTypeId.equals(task.getProductTypeId()))
                .filter(task -> rawStatus != null
                        || task.getStatus() == WorkProcessTask.Status.PENDING
                        || task.getStatus() == WorkProcessTask.Status.IN_PROGRESS)
                .toList();

        List<Map<String, Object>> items = tasks.stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId());
            item.put("productionBatchId", task.getProductionBatchId());
            item.put("batchNumber", task.getBatchNumber());
            item.put("processName", task.getProcessName());
            item.put("productName", task.getProductTypeName());
            item.put("status", task.getStatus());
            item.put("plannedQuantity", task.getPlannedQuantity());
            item.put("actualQuantity", task.getActualQuantity());
            item.put("unit", task.getPlannedUnit());
            return item;
        }).toList();

        log.info("Canonical work process task query: factoryId={}, count={}", factoryId, items.size());
        return Map.of(
                "message", String.format("查询到 %d 个工序任务", items.size()),
                "data", Map.of("tasks", items, "total", items.size()));
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return "status".equals(paramName) ? "请问要查看哪种状态的工序任务？" : null;
    }
}
