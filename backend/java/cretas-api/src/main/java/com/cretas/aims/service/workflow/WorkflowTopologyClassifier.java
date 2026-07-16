package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Classifies a Workflow from graph shape only; product_type_id is only a legacy persistence anchor. */
public final class WorkflowTopologyClassifier {

    private WorkflowTopologyClassifier() {
    }

    public static WorkflowTopology classify(ProductProcessWorkflowDTO definition) {
        if (definition == null || definition.getNodes() == null || definition.getEdges() == null) {
            return new WorkflowTopology(WorkflowTopology.Type.INVALID, List.of(), List.of());
        }
        Set<String> withIncoming = definition.getEdges().stream()
                .map(ProductProcessWorkflowDTO.Edge::getTarget)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> withOutgoing = definition.getEdges().stream()
                .map(ProductProcessWorkflowDTO.Edge::getSource)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> terminals = new TreeSet<>();
        Set<String> roots = new TreeSet<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getId() == null || node.getData() == null) continue;
            String skuId = stringValue(node.getData(), "skuId");
            if (skuId == null) continue;
            if ("FINISHED_GOOD".equals(node.getKind()) && !withOutgoing.contains(node.getId())) {
                terminals.add(skuId);
            }
            if ("RAW_MATERIAL".equals(node.getKind()) && !withIncoming.contains(node.getId())) {
                roots.add(skuId);
            }
        }
        WorkflowTopology.Type type;
        if (terminals.size() == 1) {
            type = WorkflowTopology.Type.SINGLE_OUTPUT_PRODUCT;
        } else if (terminals.size() > 1 && roots.size() == 1) {
            type = WorkflowTopology.Type.RAW_MATERIAL_SPLIT;
        } else if (terminals.size() > 1 && roots.size() > 1) {
            type = WorkflowTopology.Type.JOINT_PRODUCTION;
        } else {
            type = WorkflowTopology.Type.INVALID;
        }
        return new WorkflowTopology(type, List.copyOf(terminals), List.copyOf(roots));
    }

    private static String stringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
