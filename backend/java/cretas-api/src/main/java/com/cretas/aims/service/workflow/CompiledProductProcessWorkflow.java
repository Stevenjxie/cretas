package com.cretas.aims.service.workflow;

import java.util.List;

public record CompiledProductProcessWorkflow(
        String nodesJson,
        String edgesJson,
        List<CompiledTask> processTasks,
        List<CompiledPort> ports) {

    public CompiledProductProcessWorkflow {
        processTasks = List.copyOf(processTasks);
        ports = List.copyOf(ports);
    }

    public List<CompiledTask> allProcessTasks() {
        return processTasks;
    }

    public List<CompiledTask> reportableTasks() {
        return processTasks.stream()
                .filter(CompiledTask::reportingRequired)
                .toList();
    }

    public List<CompiledPort> portsFor(String workflowNodeId) {
        return ports.stream()
                .filter(port -> port.workflowNodeId().equals(workflowNodeId))
                .toList();
    }

    public record CompiledTask(
            String workflowNodeId,
            String workProcessId,
            int processOrder,
            String plannedUnit,
            Integer estimatedMinutes,
            boolean reportingRequired) {
    }

    public record CompiledPort(
            String workflowNodeId,
            String workflowPortId,
            String direction,
            int ordinal,
            String materialNodeId,
            String materialKind,
            String skuId,
            String unit,
            boolean required,
            String conversionMode,
            String conversionExpression) {
    }
}
