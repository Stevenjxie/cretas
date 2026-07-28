package com.cretas.aims.service.workflow;

import java.util.Map;

/** Immutable workflow/output-unit snapshot stored on a production plan. */
public record WorkflowPlanOutputContract(
        Long workflowId,
        Integer definitionVersion,
        Long revisionId,
        String revisionHash,
        Map<String, String> outputUnitBySku,
        String plannedUnit) {
}
