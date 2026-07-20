package com.cretas.aims.dto.restaurantagent;

import java.util.List;

/** Typed, closed-over-run context reconstructed exclusively from durable replay. */
public record RestaurantAgentActionProposalContext(
        String runId,
        String proposalCode,
        String actionCode,
        String executionMode,
        List<String> rationaleCodes,
        List<EvidenceReference> evidenceReferences,
        String outcomeDigest) {

    public RestaurantAgentActionProposalContext {
        rationaleCodes = List.copyOf(rationaleCodes);
        evidenceReferences = List.copyOf(evidenceReferences);
    }

    public record EvidenceReference(
            String evidenceId,
            String factId,
            String statementCode,
            String metric,
            String value,
            String unit) {
    }
}
