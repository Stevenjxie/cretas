package com.cretas.aims.ai.workflow.inventory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable result returned to the legacy Skill compatibility adapter. */
public record InventoryAnalysisWorkflowResult(
        boolean success,
        Map<String, Object> data,
        List<String> executedTools,
        String message) {

    public InventoryAnalysisWorkflowResult {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(data, "data")));
        executedTools = List.copyOf(Objects.requireNonNull(executedTools, "executedTools"));
        message = message == null ? "" : message;
    }

    public static InventoryAnalysisWorkflowResult failed(
            List<String> executedTools, String message) {
        return new InventoryAnalysisWorkflowResult(false, Map.of(), executedTools, message);
    }
}
