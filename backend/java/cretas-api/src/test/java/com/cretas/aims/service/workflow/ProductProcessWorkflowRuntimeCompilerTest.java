package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductProcessWorkflowRuntimeCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProductProcessWorkflowRuntimeCompiler compiler =
            new ProductProcessWorkflowRuntimeCompiler(
                    objectMapper, new ProductProcessWorkflowValidator());

    @Test
    void compilesRepeatedProcessBranchesAndPortsWithoutCanvasFields() throws Exception {
        ProductProcessWorkflowDTO definition = repeatedBranchedWorkflow();

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertEquals(List.of("cook-a", "cook-b", "pack"),
                compiled.reportableTasks().stream()
                        .map(CompiledProductProcessWorkflow.CompiledTask::workflowNodeId)
                        .toList());
        assertEquals(2, compiled.reportableTasks().stream()
                .filter(task -> task.workProcessId().equals("COOK"))
                .count());
        assertEquals(List.of(1, 2, 3), compiled.reportableTasks().stream()
                .map(CompiledProductProcessWorkflow.CompiledTask::processOrder)
                .toList());
        assertFalse(compiled.nodesJson().contains("position"));
        assertFalse(compiled.edgesJson().contains("viewport"));
        assertEquals(7, objectMapper.readTree(compiled.nodesJson()).size());
        assertEquals(List.of("in-raw", "out-cooked"),
                compiled.portsFor("cook-a").stream()
                        .map(CompiledProductProcessWorkflow.CompiledPort::workflowPortId)
                        .toList());
    }

    @Test
    void compilesLinearWorkflowTaskFields() {
        ProductProcessWorkflowDTO definition = linearWorkflow();

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertEquals(List.of(
                new CompiledProductProcessWorkflow.CompiledTask(
                        "cut", "CUT", 1, "kg", 12, true),
                new CompiledProductProcessWorkflow.CompiledTask(
                        "pack", "PACK", 2, "box", 5, true)),
                compiled.reportableTasks());
    }

    @Test
    void compilesMergeWithMultipleInputsAndOutputs() {
        ProductProcessWorkflowDTO definition = multiInputOutputWorkflow();

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertEquals(List.of("in-a", "in-b", "out-main", "out-side"),
                compiled.portsFor("mix").stream()
                        .map(CompiledProductProcessWorkflow.CompiledPort::workflowPortId)
                        .toList());
        assertEquals(List.of("SKU-A", "SKU-B", "SKU-MAIN", "SKU-SIDE"),
                compiled.portsFor("mix").stream()
                        .map(CompiledProductProcessWorkflow.CompiledPort::skuId)
                        .toList());
    }

    @Test
    void preservesDistinctPortsThatBindDifferentMaterialNodesWithTheSameSku() {
        ProductProcessWorkflowDTO definition = multiInputOutputWorkflow();
        materialData(definition, "raw-b").put("skuId", "SKU-A");

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertEquals(List.of("in-a", "in-b"), compiled.portsFor("mix").stream()
                .filter(port -> port.direction().equals("INPUT"))
                .map(CompiledProductProcessWorkflow.CompiledPort::workflowPortId)
                .toList());
        assertEquals(2, compiled.portsFor("mix").stream()
                .filter(port -> "SKU-A".equals(port.skuId()))
                .count());
    }

    @Test
    void ordersSameLayerProcessNodesByNodeIdNotCanvasOrInputOrder() {
        ProductProcessWorkflowDTO definition = repeatedBranchedWorkflow();
        definition.setNodes(new ArrayList<>(definition.getNodes().reversed()));
        definition.getNodes().stream().filter(node -> "cook-a".equals(node.getId()))
                .findFirst().orElseThrow()
                .setPosition(new ProductProcessWorkflowDTO.Position(999D, 999D));

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertEquals(List.of("cook-a", "cook-b", "pack"),
                compiled.reportableTasks().stream()
                        .map(CompiledProductProcessWorkflow.CompiledTask::workflowNodeId)
                        .toList());
    }

    @Test
    void retainsNonReportableNodeInSnapshotButOmitsItsTask() {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        processData(definition, "cut").put("reportingRequired", false);

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertTrue(compiled.nodesJson().contains("\"id\":\"cut\""));
        assertEquals(List.of("pack"), compiled.reportableTasks().stream()
                .map(CompiledProductProcessWorkflow.CompiledTask::workflowNodeId)
                .toList());
        assertEquals(2, compiled.allProcessTasks().size());
        assertFalse(compiled.allProcessTasks().getFirst().reportingRequired());
        assertEquals(1, compiled.allProcessTasks().getFirst().processOrder());
        assertEquals(2, compiled.reportableTasks().getFirst().processOrder());
    }

    @Test
    void rejectsMissingDeclaredPortEdgeAndMissingMaterialNode() {
        ProductProcessWorkflowDTO missingEdge = linearWorkflow();
        missingEdge.getEdges().removeIf(edge -> "cut-out".equals(edge.getId()));

        BusinessException edgeError = assertThrows(
                BusinessException.class, () -> compiler.compile(missingEdge));
        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", edgeError.getErrorCode());

        ProductProcessWorkflowDTO missingMaterial = linearWorkflow();
        missingMaterial.getNodes().removeIf(node -> "semi".equals(node.getId()));
        BusinessException materialError = assertThrows(
                BusinessException.class, () -> compiler.compile(missingMaterial));
        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", materialError.getErrorCode());
    }

    @Test
    void rejectsCycleDefensively() {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        processPorts(definition, "cut").add(port(
                "cut-loop", "INPUT", "semi", "SEMI_FINISHED", "kg", 1));
        definition.getEdges().add(edge(
                "semi-cut-loop", "semi", "output", "cut", "cut-loop"));

        BusinessException error = assertThrows(
                BusinessException.class, () -> compiler.compile(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
    }

    private ProductProcessWorkflowDTO repeatedBranchedWorkflow() {
        ProductProcessWorkflowDTO definition = workflow();
        definition.setNodes(new ArrayList<>(List.of(
                material("raw", "RAW_MATERIAL", "SKU-RAW"),
                process("cook-b", "COOK", "kg", 20, true, List.of(
                        port("in-raw", "INPUT", "raw", "RAW_MATERIAL", "kg", 0),
                        port("out-cooked", "OUTPUT", "cooked-b", "SEMI_FINISHED", "kg", 0))),
                material("cooked-b", "SEMI_FINISHED", "SKU-COOKED-B"),
                process("cook-a", "COOK", "kg", 20, true, List.of(
                        port("in-raw", "INPUT", "raw", "RAW_MATERIAL", "kg", 0),
                        port("out-cooked", "OUTPUT", "cooked-a", "SEMI_FINISHED", "kg", 0))),
                material("cooked-a", "SEMI_FINISHED", "SKU-COOKED-A"),
                process("pack", "PACK", "box", 5, true, List.of(
                        port("in-a", "INPUT", "cooked-a", "SEMI_FINISHED", "kg", 0),
                        port("in-b", "INPUT", "cooked-b", "SEMI_FINISHED", "kg", 1),
                        port("out", "OUTPUT", "finished", "FINISHED_GOOD", "box", 0))),
                material("finished", "FINISHED_GOOD", "SKU-FINISHED"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge("raw-a", "raw", "output", "cook-a", "in-raw"),
                edge("a-out", "cook-a", "out-cooked", "cooked-a", "input"),
                edge("raw-b", "raw", "output", "cook-b", "in-raw"),
                edge("b-out", "cook-b", "out-cooked", "cooked-b", "input"),
                edge("a-pack", "cooked-a", "output", "pack", "in-a"),
                edge("b-pack", "cooked-b", "output", "pack", "in-b"),
                edge("pack-out", "pack", "out", "finished", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO linearWorkflow() {
        ProductProcessWorkflowDTO definition = workflow();
        definition.setNodes(new ArrayList<>(List.of(
                material("raw", "RAW_MATERIAL", "SKU-RAW"),
                process("cut", "CUT", "kg", 12, true, List.of(
                        port("cut-in", "INPUT", "raw", "RAW_MATERIAL", "kg", 0),
                        port("cut-out", "OUTPUT", "semi", "SEMI_FINISHED", "kg", 0))),
                material("semi", "SEMI_FINISHED", "SKU-SEMI"),
                process("pack", "PACK", "box", 5, true, List.of(
                        port("pack-in", "INPUT", "semi", "SEMI_FINISHED", "kg", 0),
                        port("pack-out", "OUTPUT", "finished", "FINISHED_GOOD", "box", 0))),
                material("finished", "FINISHED_GOOD", "SKU-FINISHED"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge("raw-cut", "raw", "output", "cut", "cut-in"),
                edge("cut-out", "cut", "cut-out", "semi", "input"),
                edge("semi-pack", "semi", "output", "pack", "pack-in"),
                edge("pack-out", "pack", "pack-out", "finished", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO multiInputOutputWorkflow() {
        ProductProcessWorkflowDTO definition = workflow();
        definition.setNodes(new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL", "SKU-A"),
                material("raw-b", "RAW_MATERIAL", "SKU-B"),
                process("mix", "MIX", "kg", 30, true, List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", "kg", 0),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", "kg", 1),
                        port("out-main", "OUTPUT", "finished", "FINISHED_GOOD", "kg", 0),
                        port("out-side", "OUTPUT", "side", "SEMI_FINISHED", "kg", 1))),
                material("finished", "FINISHED_GOOD", "SKU-MAIN"),
                material("side", "SEMI_FINISHED", "SKU-SIDE"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge("a-mix", "raw-a", "output", "mix", "in-a"),
                edge("b-mix", "raw-b", "output", "mix", "in-b"),
                edge("mix-main", "mix", "out-main", "finished", "input"),
                edge("mix-side", "mix", "out-side", "side", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO workflow() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(14D, 28D, 1.2D));
        return definition;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind, String skuId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", id);
        data.put("skuId", skuId);
        data.put("skuCode", "CODE-" + skuId);
        return new ProductProcessWorkflowDTO.Node(
                id, kind, new ProductProcessWorkflowDTO.Position(10D, 20D), data);
    }

    private ProductProcessWorkflowDTO.Node process(
            String id,
            String workProcessId,
            String outputUnit,
            Integer estimatedMinutes,
            boolean reportingRequired,
            List<Map<String, Object>> ports) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", id);
        data.put("outputUnit", outputUnit);
        data.put("standardTime", estimatedMinutes);
        data.put("reportingRequired", reportingRequired);
        data.put("ports", new ArrayList<>(ports));
        data.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        return new ProductProcessWorkflowDTO.Node(
                id, "PROCESS", new ProductProcessWorkflowDTO.Position(300D, 200D), data);
    }

    private Map<String, Object> port(
            String id,
            String direction,
            String materialNodeId,
            String materialKind,
            String unit,
            int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", materialKind);
        port.put("unit", unit);
        port.put("ordinal", ordinal);
        return port;
    }

    private ProductProcessWorkflowDTO.Edge edge(
            String id, String source, String sourceHandle, String target, String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(id, source, sourceHandle, target, targetHandle);
    }

    private Map<String, Object> processData(ProductProcessWorkflowDTO definition, String nodeId) {
        return definition.getNodes().stream()
                .filter(node -> nodeId.equals(node.getId()))
                .findFirst().orElseThrow().getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> processPorts(
            ProductProcessWorkflowDTO definition, String nodeId) {
        return (List<Map<String, Object>>) processData(definition, nodeId).get("ports");
    }

    private Map<String, Object> materialData(ProductProcessWorkflowDTO definition, String nodeId) {
        return definition.getNodes().stream()
                .filter(node -> nodeId.equals(node.getId()))
                .findFirst().orElseThrow().getData();
    }
}
