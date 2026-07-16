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
        List<ProcessStep> processes) {

    public record ProcessStep(String processNodeId, String workProcessId, int order) { }
}
