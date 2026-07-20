package com.cretas.aims.ai.workflow.inventory;

import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic presenter for inventory analysis; it never invokes an LLM. */
@Component
public final class InventoryAnalysisPresenter {

    public Map<String, Object> present(
            FactoryCapabilityPack pack,
            List<ToolExecutionResult> results) {
        if (results.size() != InventoryAnalysisWorkflow.APPROVED_TOOLS.size()) {
            throw new IllegalArgumentException("inventory workflow requires exactly three results");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> packMetadata = new LinkedHashMap<>();
        packMetadata.put("packId", pack.packId());
        packMetadata.put("version", pack.version());
        packMetadata.put("digest", pack.digest());
        data.put("workflow", InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME);
        data.put("capabilityPack", packMetadata);
        data.put("inventorySummary", results.get(0).payload());
        data.put("batchInventory", results.get(1).payload());
        data.put("expiredInventory", results.get(2).payload());
        data.put("executionOrder", InventoryAnalysisWorkflow.APPROVED_TOOLS);
        data.put("toolCount", InventoryAnalysisWorkflow.APPROVED_TOOLS.size());
        return data;
    }
}
