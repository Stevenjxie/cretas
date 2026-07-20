package com.cretas.aims.service.workflow;

import java.util.List;

/**
 * The unique process path that produces one finished product from an active workflow.
 */
public record WorkflowProcessPath(
        long workflowId,
        int definitionVersion,
        String ownerId,
        String ownerType,
        String terminalProductTypeId,
        String rawRootMaterialTypeId,
        List<String> rawRootMaterialTypeIds,
        List<ProcessStep> processes) {

    /** Backward-compatible constructor for legacy single-root call sites. */
    public WorkflowProcessPath(
            long workflowId,
            int definitionVersion,
            String ownerId,
            String ownerType,
            String terminalProductTypeId,
            String rawRootMaterialTypeId,
            List<ProcessStep> processes) {
        this(workflowId, definitionVersion, ownerId, ownerType, terminalProductTypeId,
                rawRootMaterialTypeId,
                rawRootMaterialTypeId == null ? List.of() : List.of(rawRootMaterialTypeId),
                processes);
    }

    public record ProcessStep(String processNodeId, String workProcessId, int order) { }
}
