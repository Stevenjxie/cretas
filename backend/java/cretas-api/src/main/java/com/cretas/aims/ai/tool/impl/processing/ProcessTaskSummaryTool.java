package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.service.ProcessWorkReportingService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessTaskSummaryTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;
    private final ProcessWorkReportingService reportingService;

    @Override
    public String getToolName() {
        return "process_task_summary";
    }

    @Override
    public String getDescription() {
        return "查询工序任务摘要、参与人员和报工记录。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("taskId", Map.of("type", "string", "description", "工序任务ID")),
                "required", List.of("taskId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("taskId");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        String taskId = getString(params, "taskId");
        Long canonicalId = Long.valueOf(taskId.startsWith("WPT-") ? taskId.substring(4) : taskId);
        WorkProcessTaskDTO task = workProcessTaskService.getById(factoryId, canonicalId);
        List<WorkProcessTaskDTO.WorkerSummary> workers = reportingService
                .getWorkerSummaryByTask(factoryId, String.valueOf(canonicalId));
        int totalReports = reportingService.getReportsByTask(factoryId, String.valueOf(canonicalId)).size();
        BigDecimal pending = workers.stream()
                .map(WorkProcessTaskDTO.WorkerSummary::getPendingQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("productionBatchId", task.getProductionBatchId());
        data.put("processName", task.getProcessName());
        data.put("productName", task.getProductTypeName());
        data.put("status", task.getStatus());
        data.put("plannedQuantity", task.getPlannedQuantity());
        data.put("actualQuantity", task.getActualQuantity());
        data.put("pendingQuantity", pending);
        data.put("unit", task.getPlannedUnit());
        data.put("totalWorkers", workers.size());
        data.put("totalReports", totalReports);
        data.put("workers", workers);
        log.info("Canonical work process task summary: factoryId={}, taskId={}", factoryId, canonicalId);
        return Map.of("message", "工序任务摘要查询完成", "data", data);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return "taskId".equals(paramName) ? "请提供要查询的工序任务ID" : null;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
