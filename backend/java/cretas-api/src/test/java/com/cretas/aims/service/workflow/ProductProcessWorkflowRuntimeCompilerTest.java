package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductProcessWorkflowRuntimeCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UnitContractService unitContractService = mock(UnitContractService.class);
    private final WorkflowReportingUnitResolver reportingUnitResolver =
            mock(WorkflowReportingUnitResolver.class);
    private ProductProcessWorkflowRuntimeCompiler compiler;

    @BeforeEach
    void setUpCompiler() {
        when(unitContractService.normalize(any(), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = "公斤".equals(raw) ? "kg" : raw;
            return new UnitNormalizationResult(raw, code, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        when(reportingUnitResolver.resolve(any(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        compiler = new ProductProcessWorkflowRuntimeCompiler(
                objectMapper, new ProductProcessWorkflowValidator(), unitContractService,
                reportingUnitResolver);
    }

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
        JsonNode runtimeNodes = objectMapper.readTree(compiled.nodesJson());
        assertEquals(7, runtimeNodes.size());
        assertTrue(runtimeNodes.findValues("viewport").isEmpty());
        assertTrue(runtimeNodes.findValues("position").isEmpty());
        JsonNode cookA = runtimeNode(runtimeNodes, "cook-a");
        assertEquals("COOK", cookA.path("data").path("workProcessId").asText());
        assertEquals("ACTUAL_WEIGHT",
                cookA.path("data").path("conversionRule").path("mode").asText());
        assertEquals(2, cookA.path("data").path("ports").size());
        assertEdgesSnapshotMatchesDefinition(definition, compiled.edgesJson());
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
    void derivesPlannedUnitFromPrimaryOutputAndRejectsConflictingSummaryUnit() {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        processData(definition, "cut").put("outputUnit", "box");

        BusinessException error = assertThrows(BusinessException.class, () -> compiler.compile(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_RUNTIME_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("primary output port"));
    }

    @Test
    void carriesConversionReferenceAndVersionIntoCompiledPort() {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        Map<String, Object> output = processPorts(definition, "pack").stream()
                .filter(port -> "OUTPUT".equals(port.get("direction")))
                .findFirst().orElseThrow();
        output.put("conversionRefId", "conv-200g");
        output.put("conversionVersion", 7L);

        CompiledProductProcessWorkflow.CompiledPort compiled = compiler.compile(definition)
                .portsFor("pack").stream()
                .filter(port -> "OUTPUT".equals(port.direction()))
                .findFirst().orElseThrow();

        assertEquals("conv-200g", compiled.conversionRefId());
        assertEquals(7L, compiled.conversionVersion());
    }

    @Test
    void compilesPortStandardQuantityAndQuantityMode() throws Exception {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        Map<String, Object> output = processPorts(definition, "pack").stream()
                .filter(port -> "OUTPUT".equals(port.get("direction")))
                .findFirst().orElseThrow();
        output.put("standardQuantity", new BigDecimal("2.500000"));
        output.put("quantityMode", "FIXED_RATIO");

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);
        CompiledProductProcessWorkflow.CompiledPort compiledOutput = compiled
                .portsFor("pack").stream()
                .filter(port -> "OUTPUT".equals(port.direction()))
                .findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("2.500000")
                .compareTo(compiledOutput.standardQuantity()));
        assertEquals("FIXED_RATIO", compiledOutput.quantityMode());
        JsonNode runtimePack = runtimeNode(objectMapper.readTree(compiled.nodesJson()), "pack");
        JsonNode runtimeOutput = runtimePack.path("data").path("ports").get(1);
        assertEquals(0, new BigDecimal("2.500000")
                .compareTo(runtimeOutput.path("standardQuantity").decimalValue()));
        assertEquals("FIXED_RATIO", runtimeOutput.path("quantityMode").asText());
    }

    @Test
    void keepsLegacyPortsWithoutQuantityFieldsCompatible() {
        CompiledProductProcessWorkflow.CompiledPort compiled = compiler.compile(linearWorkflow())
                .portsFor("cut").getFirst();

        assertEquals(null, compiled.standardQuantity());
        assertEquals(null, compiled.quantityMode());
    }

    @Test
    void rejectsUnknownQuantityModeAndInvalidStandardQuantity() {
        ProductProcessWorkflowDTO unknownMode = linearWorkflow();
        processPorts(unknownMode, "cut").getFirst().put("quantityMode", "ESTIMATED");
        assertRuntimeInvalid(unknownMode, "cut", "data.ports[0].quantityMode");

        for (Object invalid : List.of(0, -1, "1", Double.NaN, Double.POSITIVE_INFINITY)) {
            ProductProcessWorkflowDTO definition = linearWorkflow();
            processPorts(definition, "cut").getFirst().put("standardQuantity", invalid);

            assertRuntimeInvalid(definition, "cut", "data.ports[0].standardQuantity");
        }
    }

    @Test
    void snapshotsCanonicalMaterialProcessAndPortUnitsInNodesJson() throws Exception {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        materialData(definition, "raw").put("baseUnit", "公斤");
        processData(definition, "cut").put("outputUnit", "公斤");
        processPorts(definition, "cut").forEach(port -> port.put("unit", "公斤"));

        JsonNode nodes = objectMapper.readTree(compiler.compile(definition).nodesJson());

        assertEquals("kg", runtimeNode(nodes, "raw").path("data").path("baseUnit").asText());
        assertEquals("kg", runtimeNode(nodes, "cut").path("data").path("outputUnit").asText());
        assertTrue(runtimeNode(nodes, "cut").path("data").path("ports").findValues("unit")
                .stream().allMatch(unit -> "kg".equals(unit.asText())));
    }

    @Test
    void projectsLegacyGraphUnitsToAuthoritativeReportingUnits() throws Exception {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        materialData(definition, "raw").put("baseUnit", "g");
        materialData(definition, "semi").put("baseUnit", "g");
        processData(definition, "cut").put("outputUnit", "g");
        processPorts(definition, "cut").forEach(port -> port.put("unit", "g"));
        processPorts(definition, "pack").getFirst().put("unit", "g");
        when(reportingUnitResolver.resolve(any(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> switch ((String) invocation.getArgument(1)) {
                    case "RAW_MATERIAL", "SEMI_FINISHED" -> "kg";
                    case "FINISHED_GOOD" -> "盒";
                    default -> invocation.getArgument(3);
                });

        CompiledProductProcessWorkflow compiled = compiler.compile(definition);

        assertEquals("kg", compiled.reportableTasks().getFirst().plannedUnit());
        assertEquals("盒", compiled.reportableTasks().getLast().plannedUnit());
        assertEquals(List.of("kg", "kg", "kg", "盒"), compiled.ports().stream()
                .map(CompiledProductProcessWorkflow.CompiledPort::unit)
                .toList());
        JsonNode nodes = objectMapper.readTree(compiled.nodesJson());
        assertEquals("kg", runtimeNode(nodes, "raw").path("data").path("baseUnit").asText());
        assertEquals("kg", runtimeNode(nodes, "semi").path("data").path("baseUnit").asText());
        assertEquals("盒", runtimeNode(nodes, "finished").path("data").path("baseUnit").asText());
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

    @Test
    void rejectsStringReportingRequiredWithRuntimeFieldContext() {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        processData(definition, "cut").put("reportingRequired", "false");

        assertRuntimeInvalid(definition, "cut", "data.reportingRequired");
    }

    @Test
    void rejectsStringAndOutOfRangeStandardTimeWithRuntimeFieldContext() {
        for (Object invalid : List.of("12", (long) Integer.MAX_VALUE + 1L)) {
            ProductProcessWorkflowDTO definition = linearWorkflow();
            processData(definition, "cut").put("standardTime", invalid);

            assertRuntimeInvalid(definition, "cut", "data.standardTime");
        }
    }

    @Test
    void rejectsMalformedConversionRuleAndWrongFieldTypesWithRuntimeFieldContext() {
        ProductProcessWorkflowDTO nonObject = linearWorkflow();
        processData(nonObject, "cut").put("conversionRule", "ACTUAL_WEIGHT");
        assertRuntimeInvalid(nonObject, "cut", "data.conversionRule");

        ProductProcessWorkflowDTO wrongMode = linearWorkflow();
        processData(wrongMode, "cut").put(
                "conversionRule", Map.of("mode", 7));
        assertRuntimeInvalid(wrongMode, "cut", "data.conversionRule.mode");

        ProductProcessWorkflowDTO wrongExpression = linearWorkflow();
        processData(wrongExpression, "cut").put(
                "conversionRule", Map.of("mode", "FORMULA", "expression", true));
        assertRuntimeInvalid(wrongExpression, "cut", "data.conversionRule.expression");
    }

    @Test
    void rejectsFractionalAndOutOfRangePortOrdinalWithRuntimeFieldContext() {
        for (Object invalid : List.of(0.5D, Long.MAX_VALUE)) {
            ProductProcessWorkflowDTO definition = linearWorkflow();
            processPorts(definition, "cut").getFirst().put("ordinal", invalid);

            assertRuntimeInvalid(definition, "cut", "data.ports[0].ordinal");
        }
    }

    @Test
    void rejectsMalformedMaterialSkuWithMaterialNodeAndFieldContext() {
        ProductProcessWorkflowDTO definition = linearWorkflow();
        materialData(definition, "raw").put("skuId", 101);

        assertRuntimeInvalid(definition, "raw", "data.skuId");
    }

    private ProductProcessWorkflowDTO repeatedBranchedWorkflow() {
        ProductProcessWorkflowDTO definition = workflow();
        definition.setNodes(new ArrayList<>(List.of(
                material("raw", "RAW_MATERIAL", "SKU-RAW", "kg"),
                process("cook-b", "COOK", "kg", 20, true, List.of(
                        port("in-raw", "INPUT", "raw", "RAW_MATERIAL", "kg", 0),
                        port("out-cooked", "OUTPUT", "cooked-b", "SEMI_FINISHED", "kg", 0))),
                material("cooked-b", "SEMI_FINISHED", "SKU-COOKED-B", "kg"),
                process("cook-a", "COOK", "kg", 20, true, List.of(
                        port("in-raw", "INPUT", "raw", "RAW_MATERIAL", "kg", 0),
                        port("out-cooked", "OUTPUT", "cooked-a", "SEMI_FINISHED", "kg", 0))),
                material("cooked-a", "SEMI_FINISHED", "SKU-COOKED-A", "kg"),
                process("pack", "PACK", "box", 5, true, List.of(
                        port("in-a", "INPUT", "cooked-a", "SEMI_FINISHED", "kg", 0),
                        port("in-b", "INPUT", "cooked-b", "SEMI_FINISHED", "kg", 1),
                        port("out", "OUTPUT", "finished", "FINISHED_GOOD", "box", 0))),
                material("finished", "FINISHED_GOOD", "SKU-FINISHED", "box"))));
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
                material("raw", "RAW_MATERIAL", "SKU-RAW", "kg"),
                process("cut", "CUT", "kg", 12, true, List.of(
                        port("cut-in", "INPUT", "raw", "RAW_MATERIAL", "kg", 0),
                        port("cut-out", "OUTPUT", "semi", "SEMI_FINISHED", "kg", 0))),
                material("semi", "SEMI_FINISHED", "SKU-SEMI", "kg"),
                process("pack", "PACK", "box", 5, true, List.of(
                        port("pack-in", "INPUT", "semi", "SEMI_FINISHED", "kg", 0),
                        port("pack-out", "OUTPUT", "finished", "FINISHED_GOOD", "box", 0))),
                material("finished", "FINISHED_GOOD", "SKU-FINISHED", "box"))));
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
                material("raw-a", "RAW_MATERIAL", "SKU-A", "kg"),
                material("raw-b", "RAW_MATERIAL", "SKU-B", "kg"),
                process("mix", "MIX", "kg", 30, true, List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", "kg", 0),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", "kg", 1),
                        port("out-main", "OUTPUT", "finished", "FINISHED_GOOD", "kg", 0),
                        port("out-side", "OUTPUT", "side", "SEMI_FINISHED", "kg", 1))),
                material("finished", "FINISHED_GOOD", "SKU-MAIN", "kg"),
                material("side", "SEMI_FINISHED", "SKU-SIDE", "kg"))));
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

    private ProductProcessWorkflowDTO.Node material(
            String id, String kind, String skuId, String baseUnit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", id);
        data.put("skuId", skuId);
        data.put("skuCode", "CODE-" + skuId);
        data.put("baseUnit", baseUnit);
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

    private void assertRuntimeInvalid(
            ProductProcessWorkflowDTO definition,
            String nodeId,
            String fieldPath) {
        BusinessException error = assertThrows(
                BusinessException.class, () -> compiler.compile(definition));
        assertEquals("PRODUCT_PROCESS_WORKFLOW_RUNTIME_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains(nodeId), error.getMessage());
        assertTrue(error.getMessage().contains(fieldPath), error.getMessage());
        assertTrue(error.getActionHint().contains(nodeId), error.getActionHint());
        assertTrue(error.getActionHint().contains(fieldPath), error.getActionHint());
    }

    private void assertEdgesSnapshotMatchesDefinition(
            ProductProcessWorkflowDTO definition,
            String edgesJson) throws Exception {
        JsonNode runtimeEdges = objectMapper.readTree(edgesJson);
        assertTrue(runtimeEdges.isArray());
        assertEquals(definition.getEdges().size(), runtimeEdges.size());
        for (int index = 0; index < definition.getEdges().size(); index++) {
            ProductProcessWorkflowDTO.Edge expected = definition.getEdges().get(index);
            JsonNode actual = runtimeEdges.get(index);
            assertEquals(expected.getId(), actual.path("id").asText());
            assertEquals(expected.getSource(), actual.path("source").asText());
            assertEquals(expected.getSourceHandle(), actual.path("sourceHandle").asText());
            assertEquals(expected.getTarget(), actual.path("target").asText());
            assertEquals(expected.getTargetHandle(), actual.path("targetHandle").asText());
            assertEquals(5, actual.size());
            assertFalse(actual.has("viewport"));
        }
    }

    private JsonNode runtimeNode(JsonNode runtimeNodes, String nodeId) {
        for (JsonNode runtimeNode : runtimeNodes) {
            if (nodeId.equals(runtimeNode.path("id").asText())) {
                return runtimeNode;
            }
        }
        throw new AssertionError("Missing runtime node: " + nodeId);
    }
}
