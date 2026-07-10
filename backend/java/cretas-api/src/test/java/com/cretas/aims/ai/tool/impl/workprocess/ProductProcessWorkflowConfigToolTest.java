package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductProcessWorkflowConfigToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolExecutor tool;

    @BeforeEach
    void setUp() {
        tool = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator());
    }

    @Test
    void mixedValidAndUnauthorizedPatchRejectsWholeBatch() throws Exception {
        Map<String, Object> envelope = preview(List.of(
                setField("process:1", "conversionRule.mode", "SUM_OUTPUTS"),
                Map.of("op", "ACTIVATE_WORKFLOW", "workflowId", 9)));

        assertFalse((Boolean) envelope.get("success"));
        assertEquals("WORKFLOW_PATCH_REJECTED", envelope.get("errorCode"));
        assertFalse(envelope.containsKey("data"));
    }

    @Test
    void definitionIsRequiredForPreview() throws Exception {
        ToolCall call = ToolCall.of(
                "missing-definition",
                tool.getToolName(),
                objectMapper.writeValueAsString(Map.of("patches", List.of())));

        Map<String, Object> envelope = objectMapper.readValue(
                tool.preview(call, Map.of("factoryId", "F006")), new TypeReference<>() {});

        assertFalse((Boolean) envelope.get("success"));
        assertEquals("WORKFLOW_DEFINITION_REQUIRED", envelope.get("errorCode"));
    }

    @Test
    void finalGraphValidationRejectsDanglingCycleInvalidHandleAndKindCrossover() throws Exception {
        List<List<Map<String, Object>>> attacks = List.of(
                List.of(Map.of("op", "UPSERT_EDGE", "edge", edge(
                        "edge:ghost", "missing", "output", "process:1", "input:1"))),
                List.of(Map.of("op", "UPSERT_EDGE", "edge", edge(
                        "edge:self", "process:1", "output:1", "process:1", "input:1"))),
                List.of(Map.of("op", "UPSERT_EDGE", "edge", edge(
                        "edge:bad-handle", "raw", "missing-output", "process:1", "input:1"))),
                List.of(setField("raw", "ports", List.of(port(
                        "bad", "INPUT", "raw", "kg", 0)))),
                List.of(setField("missing", "name", "ghost")),
                List.of(Map.of("op", "UPSERT_NODE", "node", processNode(
                        "raw", List.of(port("input:new", "INPUT", "raw", "kg", 0))))));

        for (List<Map<String, Object>> attack : attacks) {
            Map<String, Object> envelope = preview(attack);
            assertFalse((Boolean) envelope.get("success"), "attack should be rejected: " + attack);
            assertEquals("WORKFLOW_PATCH_REJECTED", envelope.get("errorCode"));
        }
    }

    @Test
    void dependentNodePortAndEdgePatchesAreAcceptedInDeclaredOrder() throws Exception {
        List<Map<String, Object>> ports = new ArrayList<>(processPorts());
        ports.add(port("output:2", "OUTPUT", "semi:2", "kg", 1));
        List<Map<String, Object>> patches = List.of(
                Map.of("op", "UPSERT_NODE", "node", materialNode("semi:2", "SEMI_FINISHED")),
                setField("process:1", "ports", ports),
                Map.of("op", "UPSERT_EDGE", "edge", edge(
                        "edge:process:semi2", "process:1", "output:2", "semi:2", "input")));

        Map<String, Object> envelope = preview(patches);

        assertTrue((Boolean) envelope.get("success"));
        Map<String, Object> data = readMap(envelope.get("data"));
        assertEquals(false, data.get("applied"));
        assertEquals(3, ((List<?>) data.get("patches")).size());
    }

    @Test
    void nullableMaterialMetadataAndProcessBoundariesAreAccepted() throws Exception {
        for (Object standardTime : List.of(0, 120.5)) {
            Map<String, Object> material = mutableNode(materialNode("raw", "RAW_MATERIAL"));
            Map<String, Object> materialData = readMap(material.get("data"));
            materialData.put("skuCode", null);
            materialData.put("specification", null);

            Map<String, Object> process = mutableNode(processNode("process:1", processPorts()));
            Map<String, Object> processData = readMap(process.get("data"));
            processData.put("processCategory", null);
            processData.put("standardTime", standardTime);

            Map<String, Object> envelope = preview(List.of(
                    Map.of("op", "UPSERT_NODE", "node", material),
                    Map.of("op", "UPSERT_NODE", "node", process)));

            assertTrue((Boolean) envelope.get("success"), "boundary should be accepted: " + standardTime);
        }

        Map<String, Object> process = mutableNode(processNode("process:1", processPorts()));
        readMap(process.get("data")).put("standardTime", null);
        Map<String, Object> envelope = preview(List.of(Map.of("op", "UPSERT_NODE", "node", process)));
        assertTrue((Boolean) envelope.get("success"), "null standardTime should be accepted");
    }

    @Test
    void overflowExponentPortOrdinalsAreRejectedThroughPublicPreview() throws Exception {
        for (String ordinalJson : List.of("1e309", "-1e309")) {
            Map<String, Object> envelope = previewRawOrdinal(ordinalJson);

            assertFalse((Boolean) envelope.get("success"), "overflow should be rejected: " + ordinalJson);
            assertEquals("WORKFLOW_PATCH_REJECTED", envelope.get("errorCode"));
        }
    }

    @Test
    void executeIsAlwaysRejectedWithSemanticErrorCode() throws Exception {
        ToolCall call = ToolCall.of(
                "execute-1",
                tool.getToolName(),
                objectMapper.writeValueAsString(Map.of("patches", List.of())));

        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(call, Map.of("factoryId", "F006")), new TypeReference<>() {});

        assertFalse((Boolean) envelope.get("success"));
        assertEquals("WORKFLOW_AI_PREVIEW_ONLY", envelope.get("errorCode"));
    }

    private Map<String, Object> preview(List<Map<String, Object>> patches) throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definition(),
                "patches", patches));
        return objectMapper.readValue(
                tool.preview(
                        ToolCall.of("preview", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
    }

    private Map<String, Object> previewRawOrdinal(String ordinalJson) throws Exception {
        String arguments = """
                {
                  "definition": %s,
                  "patches": [{
                    "op": "SET_NODE_FIELD",
                    "nodeId": "process:1",
                    "path": "ports",
                    "value": [
                      {
                        "id": "input:1",
                        "direction": "INPUT",
                        "materialNodeId": "raw",
                        "materialKind": "RAW_MATERIAL",
                        "unit": "kg",
                        "ordinal": %s
                      },
                      {
                        "id": "output:1",
                        "direction": "OUTPUT",
                        "materialNodeId": "semi",
                        "materialKind": "SEMI_FINISHED",
                        "unit": "kg",
                        "ordinal": 0
                      }
                    ]
                  }]
                }
                """.formatted(objectMapper.writeValueAsString(definition()), ordinalJson);
        return objectMapper.readValue(
                tool.preview(
                        ToolCall.of("preview-overflow", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
    }

    private Map<String, Object> definition() {
        return Map.of(
                "schemaVersion", 1,
                "status", "DRAFT",
                "version", 1,
                "nodes", List.of(
                        materialNode("raw", "RAW_MATERIAL"),
                        processNode("process:1", processPorts()),
                        materialNode("semi", "SEMI_FINISHED")),
                "edges", List.of(
                        edge("edge:raw:process", "raw", "output", "process:1", "input:1"),
                        edge("edge:process:semi", "process:1", "output:1", "semi", "input")),
                "viewport", Map.of("x", 0, "y", 0, "zoom", 1));
    }

    private Map<String, Object> materialNode(String id, String kind) {
        return Map.of(
                "id", id,
                "kind", kind,
                "position", Map.of("x", 16, "y", 32),
                "data", Map.of("name", id, "skuId", id + "-sku", "baseUnit", "kg"));
    }

    private Map<String, Object> processNode(String id, List<Map<String, Object>> ports) {
        return Map.of(
                "id", id,
                "kind", "PROCESS",
                "position", Map.of("x", 256, "y", 32),
                "data", Map.of(
                        "workProcessId", "WP-1",
                        "processName", "Cut",
                        "inputUnit", "kg",
                        "outputUnit", "kg",
                        "ports", ports,
                        "conversionRule", Map.of("mode", "ACTUAL_WEIGHT"),
                        "reportingRequired", true));
    }

    private List<Map<String, Object>> processPorts() {
        return List.of(
                port("input:1", "INPUT", "raw", "kg", 0),
                port("output:1", "OUTPUT", "semi", "kg", 0));
    }

    private Map<String, Object> port(
            String id, String direction, String materialNodeId, String unit, int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", direction.equals("INPUT") ? "RAW_MATERIAL" : "SEMI_FINISHED");
        port.put("unit", unit);
        port.put("ordinal", ordinal);
        return port;
    }

    private Map<String, Object> edge(
            String id, String source, String sourceHandle, String target, String targetHandle) {
        return Map.of(
                "id", id,
                "source", source,
                "sourceHandle", sourceHandle,
                "target", target,
                "targetHandle", targetHandle);
    }

    private Map<String, Object> setField(String nodeId, String path, Object value) {
        return Map.of("op", "SET_NODE_FIELD", "nodeId", nodeId, "path", path, "value", value);
    }

    private Map<String, Object> mutableNode(Map<String, Object> node) {
        Map<String, Object> copy = new LinkedHashMap<>(node);
        copy.put("data", new LinkedHashMap<>(readMap(node.get("data"))));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        return (Map<String, Object>) value;
    }
}
