package com.cretas.aims.dto.restaurantagent;

/** Safe projection of the approval workflow; navigation is absent until approval completes. */
public record RestaurantAgentActionWorkflowResponse(
        String schemaVersion,
        String runId,
        String proposalCode,
        String workflowKey,
        String workflowInstanceId,
        String workflowStatus,
        boolean reused,
        String navigationTarget) {
}
