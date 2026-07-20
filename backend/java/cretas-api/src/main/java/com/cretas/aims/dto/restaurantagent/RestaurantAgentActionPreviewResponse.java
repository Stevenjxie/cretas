package com.cretas.aims.dto.restaurantagent;

import java.time.LocalDateTime;
import java.util.List;

/** Server-rebuilt preview for the one allowlisted restaurant action proposal. */
public record RestaurantAgentActionPreviewResponse(
        String schemaVersion,
        String runId,
        String proposalCode,
        String actionCode,
        String executionMode,
        List<String> rationaleCodes,
        List<EvidenceReference> evidenceReferences,
        String workflowKey,
        String previewToken,
        LocalDateTime expiresAt) {

    public RestaurantAgentActionPreviewResponse {
        rationaleCodes = List.copyOf(rationaleCodes);
        evidenceReferences = List.copyOf(evidenceReferences);
    }

    public record EvidenceReference(String evidenceId, String factId, String statementCode) {
    }
}
