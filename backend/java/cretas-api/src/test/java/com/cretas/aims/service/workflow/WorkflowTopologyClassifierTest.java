package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowTopologyClassifierTest {

    @Test
    void derivesSingleOutputRegardlessOfInputCount() {
        WorkflowTopology topology = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A", "RAW-B"), List.of("FG-1")));

        assertEquals(WorkflowTopology.Type.SINGLE_OUTPUT_PRODUCT, topology.type());
        assertEquals(List.of("RAW-A", "RAW-B"), topology.rootInputSkuIds());
        assertEquals(List.of("FG-1"), topology.terminalOutputSkuIds());
        assertEquals(2, topology.logicalRootInputCount());
    }

    @Test
    void derivesRawSplitAndJointProductionFromDistinctRootSkuSet() {
        WorkflowTopology split = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A"), List.of("FG-1", "FG-2")));
        WorkflowTopology joint = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A", "RAW-B"), List.of("FG-1", "FG-2")));

        assertEquals(WorkflowTopology.Type.RAW_MATERIAL_SPLIT, split.type());
        assertEquals(WorkflowTopology.Type.JOINT_PRODUCTION, joint.type());
        assertEquals(1, split.logicalRootInputCount());
        assertEquals(2, joint.logicalRootInputCount());
    }

    @Test
    void countsMutuallySubstitutableRawMaterialsAsOneLogicalInput() {
        WorkflowTopology topology = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A", "RAW-B", "RAW-C", "RAW-D"),
                        List.of("FG-1", "FG-2"), true));

        assertEquals(WorkflowTopology.Type.RAW_MATERIAL_SPLIT, topology.type());
        assertEquals(List.of("RAW-A", "RAW-B", "RAW-C", "RAW-D"), topology.rootInputSkuIds());
        assertEquals(1, topology.logicalRootInputCount());
    }

    @Test
    void keepsSubstitutableSingleOutputRootsAsOneLogicalInput() {
        WorkflowTopology topology = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A", "RAW-B", "RAW-C", "RAW-D"),
                        List.of("FG-1"), true));

        assertEquals(WorkflowTopology.Type.SINGLE_OUTPUT_PRODUCT, topology.type());
        assertEquals(1, topology.logicalRootInputCount());
    }

    private ProductProcessWorkflowDTO graph(List<String> roots, List<String> terminals) {
        return graph(roots, terminals, false);
    }

    private ProductProcessWorkflowDTO graph(
            List<String> roots, List<String> terminals, boolean mutuallySubstitutableRoots) {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>();
        Map<String, Object> processData = new LinkedHashMap<>();
        List<Map<String, Object>> ports = new ArrayList<>();
        processData.put("ports", ports);
        nodes.add(node("process", "PROCESS", processData));
        int index = 0;
        for (String root : roots) {
            String id = "raw-" + index++;
            String portId = "input-" + id;
            nodes.add(material(id, "RAW_MATERIAL", root));
            edges.add(new ProductProcessWorkflowDTO.Edge(
                    "edge-in-" + id, id, "output", "process", portId));
            ports.add(new LinkedHashMap<>(Map.of(
                    "id", portId,
                    "direction", "INPUT",
                    "materialNodeId", id)));
        }
        if (mutuallySubstitutableRoots) {
            processData.put("portGroups", List.of(new LinkedHashMap<>(Map.of(
                    "id", "input-alternatives",
                    "direction", "INPUT",
                    "mode", "EXACTLY_ONE",
                    "portIds", ports.stream().map(port -> port.get("id")).toList()))));
        }
        index = 0;
        for (String terminal : terminals) {
            String id = "fg-" + index++;
            nodes.add(material(id, "FINISHED_GOOD", terminal));
            edges.add(new ProductProcessWorkflowDTO.Edge(
                    "edge-out-" + id, "process", "output-" + id, id, "input"));
        }
        definition.setNodes(nodes);
        definition.setEdges(edges);
        return definition;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind, String skuId) {
        return node(id, kind, new LinkedHashMap<>(Map.of("skuId", skuId)));
    }

    private ProductProcessWorkflowDTO.Node node(String id, String kind, Map<String, Object> data) {
        return new ProductProcessWorkflowDTO.Node(
                id, kind, new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }
}
