package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessTaskAnalysisTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "process_task_analysis";
    }

    @Override
    public String getDescription() {
        return "分析工序任务状态、完成量和计划延期风险。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("productionRunId", Map.of(
                        "type", "string",
                        "description", "生产批次ID，支持 BATCH-123 或 123")),
                "required", Collections.emptyList());
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
        String productionRunId = getString(params, "productionRunId");
        List<WorkProcessTaskDTO> tasks = productionRunId == null
                ? workProcessTaskService.list(factoryId, null, null, null, PageRequest.of(0, 1000)).getContent()
                : workProcessTaskService.listByBatch(factoryId, parseBatchId(productionRunId));
        if (productionRunId == null) {
            tasks = tasks.stream()
                    .filter(task -> task.getStatus() == WorkProcessTask.Status.PENDING
                            || task.getStatus() == WorkProcessTask.Status.IN_PROGRESS)
                    .toList();
        }
        return buildAnalysis(productionRunId, tasks);
    }

    private Map<String, Object> buildAnalysis(String productionRunId, List<WorkProcessTaskDTO> tasks) {
        Map<String, Long> statusCounts = tasks.stream()
                .collect(Collectors.groupingBy(task -> task.getStatus().name(), Collectors.counting()));
        BigDecimal planned = tasks.stream()
                .map(WorkProcessTaskDTO::getPlannedQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = tasks.stream()
                .map(WorkProcessTaskDTO::getActualQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal progress = planned.signum() > 0
                ? actual.multiply(BigDecimal.valueOf(100)).divide(planned, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        long atRisk = tasks.stream()
                .filter(task -> task.getStatus() == WorkProcessTask.Status.IN_PROGRESS)
                .filter(task -> task.getPlannedEndAt() != null && task.getPlannedEndAt().isBefore(LocalDateTime.now()))
                .filter(task -> task.getPlannedQuantity() != null)
                .filter(task -> Objects.requireNonNullElse(task.getActualQuantity(), BigDecimal.ZERO)
                        .compareTo(task.getPlannedQuantity()) < 0)
                .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productionRunId", productionRunId);
        data.put("totalTasks", tasks.size());
        data.put("statusDistribution", statusCounts);
        data.put("totalPlannedQuantity", planned);
        data.put("totalActualQuantity", actual);
        data.put("overallProgressPercent", progress);
        data.put("atRiskTasks", atRisk);
        data.put("tasks", tasks.stream().map(task -> Map.of(
                "id", task.getId(),
                "processName", Objects.requireNonNullElse(task.getProcessName(), ""),
                "status", task.getStatus(),
                "plannedQuantity", Objects.requireNonNullElse(task.getPlannedQuantity(), BigDecimal.ZERO),
                "actualQuantity", Objects.requireNonNullElse(task.getActualQuantity(), BigDecimal.ZERO),
                "unit", Objects.requireNonNullElse(task.getPlannedUnit(), ""))).toList());
        log.info("Canonical work process task analysis: run={}, count={}", productionRunId, tasks.size());
        return Map.of(
                "message", String.format("工序任务分析完成：共 %d 个任务，总体完成率 %s%%", tasks.size(), progress),
                "data", data);
    }

    private Long parseBatchId(String value) {
        return Long.valueOf(value.startsWith("BATCH-") ? value.substring(6) : value);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return "productionRunId".equals(paramName) ? "请提供生产批次ID，或留空分析所有活跃任务" : null;
    }
}
