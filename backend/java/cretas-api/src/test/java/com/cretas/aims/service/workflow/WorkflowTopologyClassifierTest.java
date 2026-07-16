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
    }

    @Test
    void derivesRawSplitAndJointProductionFromDistinctRootSkuSet() {
        WorkflowTopology split = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A"), List.of("FG-1", "FG-2")));
        WorkflowTopology joint = WorkflowTopologyClassifier.classify(
                graph(List.of("RAW-A", "RAW-B"), List.of("FG-1", "FG-2")));

        assertEquals(WorkflowTopology.Type.RAW_MATERIAL_SPLIT, split.type());
        assertEquals(WorkflowTopology.Type.JOINT_PRODUCTION, joint.type());
    }

    private ProductProcessWorkflowDTO graph(List<String> roots, List<String> terminals) {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>();
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("ports", List.of());
        nodes.add(node("process", "PROCESS", processData));
        int index = 0;
        for (String root : roots) {
            String id = "raw-" + index++;
            nodes.add(material(id, "RAW_MATERIAL", root));
            edges.add(new ProductProcessWorkflowDTO.Edge(
                    "edge-in-" + id, id, "output", "process", "input-" + id));
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
