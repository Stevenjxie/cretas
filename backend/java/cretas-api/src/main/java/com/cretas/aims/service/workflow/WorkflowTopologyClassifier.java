package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.List;
import java.util.HashMap;
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
            return new WorkflowTopology(WorkflowTopology.Type.INVALID, List.of(), List.of(), 0);
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
        Map<String, String> rootSkuByNodeId = new HashMap<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getId() == null || node.getData() == null) continue;
            String skuId = stringValue(node.getData(), "skuId");
            if (skuId == null) continue;
            if ("FINISHED_GOOD".equals(node.getKind()) && !withOutgoing.contains(node.getId())) {
                terminals.add(skuId);
            }
            if ("RAW_MATERIAL".equals(node.getKind()) && !withIncoming.contains(node.getId())) {
                roots.add(skuId);
                rootSkuByNodeId.put(node.getId(), skuId);
            }
        }
        int logicalRootCount = logicalRootCount(definition, rootSkuByNodeId.keySet());
        WorkflowTopology.Type type;
        if (terminals.size() == 1) {
            type = WorkflowTopology.Type.SINGLE_OUTPUT_PRODUCT;
        } else if (terminals.size() > 1 && logicalRootCount == 1) {
            type = WorkflowTopology.Type.RAW_MATERIAL_SPLIT;
        } else if (terminals.size() > 1 && logicalRootCount > 1) {
            type = WorkflowTopology.Type.JOINT_PRODUCTION;
        } else {
            type = WorkflowTopology.Type.INVALID;
        }
        return new WorkflowTopology(type, List.copyOf(terminals), List.copyOf(roots), logicalRootCount);
    }

    /**
     * A group of root raw-material ports configured as EXACTLY_ONE is one logical input: the
     * materials are alternatives, not simultaneous requirements. Other roots remain independent.
     */
    private static int logicalRootCount(
            ProductProcessWorkflowDTO definition, Set<String> rootNodeIds) {
        if (rootNodeIds.isEmpty()) return 0;
        Map<String, String> parent = new HashMap<>();
        rootNodeIds.forEach(id -> parent.put(id, id));
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
            Map<String, String> materialNodeByPortId = inputMaterialNodeByPortId(node.getData());
            Object rawGroups = node.getData().get("portGroups");
            if (!(rawGroups instanceof List<?> groups)) continue;
            for (Object rawGroup : groups) {
                if (!(rawGroup instanceof Map<?, ?> group)
                        || !"INPUT".equals(stringValue(group, "direction"))
                        || !"EXACTLY_ONE".equals(stringValue(group, "mode"))) {
                    continue;
                }
                Object rawPortIds = group.get("portIds");
                if (!(rawPortIds instanceof List<?> portIds)) continue;
                List<String> alternativeRoots = portIds.stream()
                        .map(String::valueOf)
                        .map(materialNodeByPortId::get)
                        .filter(rootNodeIds::contains)
                        .distinct()
                        .toList();
                if (alternativeRoots.size() < 2) continue;
                String first = alternativeRoots.getFirst();
                alternativeRoots.stream().skip(1).forEach(root -> union(parent, first, root));
            }
        }
        return (int) rootNodeIds.stream().map(id -> find(parent, id)).distinct().count();
    }

    private static Map<String, String> inputMaterialNodeByPortId(Map<String, Object> data) {
        Map<String, String> result = new HashMap<>();
        Object rawPorts = data.get("ports");
        if (!(rawPorts instanceof List<?> ports)) return result;
        for (Object rawPort : ports) {
            if (!(rawPort instanceof Map<?, ?> port)
                    || !"INPUT".equals(stringValue(port, "direction"))) {
                continue;
            }
            String portId = stringValue(port, "id");
            String materialNodeId = stringValue(port, "materialNodeId");
            if (portId != null && materialNodeId != null) result.put(portId, materialNodeId);
        }
        return result;
    }

    private static void union(Map<String, String> parent, String left, String right) {
        String leftRoot = find(parent, left);
        String rightRoot = find(parent, right);
        if (!leftRoot.equals(rightRoot)) parent.put(rightRoot, leftRoot);
    }

    private static String find(Map<String, String> parent, String value) {
        String current = parent.getOrDefault(value, value);
        while (!current.equals(parent.getOrDefault(current, current))) {
            current = parent.get(current);
        }
        parent.put(value, current);
        return current;
    }

    private static String stringValue(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
