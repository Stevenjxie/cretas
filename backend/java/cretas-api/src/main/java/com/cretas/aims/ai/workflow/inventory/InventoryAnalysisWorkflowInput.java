package com.cretas.aims.ai.workflow.inventory;

/** Trusted identity and bounded request data for the canonical inventory analysis workflow. */
public record InventoryAnalysisWorkflowInput(
        String factoryId,
        long userId,
        String sessionId,
        String userQuery,
        long timeoutMs) {

    public InventoryAnalysisWorkflowInput {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (userQuery == null || userQuery.isBlank()) {
            throw new IllegalArgumentException("userQuery is required");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        factoryId = factoryId.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
        userQuery = userQuery.trim();
    }
}
