package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.List;

/** Target-SKU reverse slice of the exact immutable Workflow revision pinned by one BOM. */
public record PinnedWorkflowGraph(
        Long workflowRevisionId,
        Long workflowId,
        Integer definitionVersion,
        String revisionHash,
        String targetProductTypeId,
        String terminalNodeId,
        List<String> rootMaterialTypeIds,
        List<ProcessStep> processes,
        List<ProductProcessWorkflowDTO.Node> nodes,
        List<ProductProcessWorkflowDTO.Edge> edges) {

    public record ProcessStep(String processNodeId, String workProcessId, int order) { }
}
