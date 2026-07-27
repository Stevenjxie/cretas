package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical authoring semantics for newly saved Workflow drafts.
 *
 * <p>Workflow owns the possible process interfaces. The pinned BOM owns main/substitute
 * authorization, while a formal process report records which allowed inputs and outputs
 * actually occurred. Historical revisions without this marker keep their legacy contract.
 */
public final class WorkflowActualIoSemantics {

    public static final String MODE_FIELD = "reportingSelectionMode";
    public static final String ACTUAL_IO = "ACTUAL_IO";

    private WorkflowActualIoSemantics() {
    }

    public static boolean enabled(ProductProcessWorkflowDTO.Node node) {
        return node != null
                && "PROCESS".equals(node.getKind())
                && node.getData() != null
                && ACTUAL_IO.equals(node.getData().get(MODE_FIELD));
    }

    /**
     * Normalize only the mutable request DTO before validation and snapshot capture.
     * Immutable historical revision rows are never rewritten.
     */
    public static void normalizeDraft(ProductProcessWorkflowDTO definition) {
        if (definition == null || definition.getNodes() == null) {
            return;
        }
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>(node.getData());
            data.put(MODE_FIELD, ACTUAL_IO);
            data.remove("portGroups");
            data.remove("inputRequirementGroups");

            Object rawPorts = data.get("ports");
            if (rawPorts instanceof List<?> ports) {
                List<Object> normalizedPorts = new ArrayList<>(ports.size());
                for (Object rawPort : ports) {
                    if (rawPort instanceof Map<?, ?> port) {
                        Map<String, Object> normalizedPort = new LinkedHashMap<>();
                        port.forEach((key, value) -> normalizedPort.put(String.valueOf(key), value));
                        normalizedPort.remove("outputRole");
                        normalizedPort.remove("costAllocationRatio");
                        normalizedPorts.add(normalizedPort);
                    } else {
                        normalizedPorts.add(rawPort);
                    }
                }
                data.put("ports", normalizedPorts);
            }
            node.setData(data);
        }
    }
}
