package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        tool = new ProductProcessWorkflowConfigTool(objectMapper);
    }

    @Test
    void previewKeepsOnlyWhitelistedWorkflowPatchOperationsAndPaths() throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "patches", List.of(
                        Map.of(
                                "op", "SET_NODE_FIELD",
                                "nodeId", "process:1",
                                "path", "conversionRule.mode",
                                "value", "ACTUAL_WEIGHT"),
                        Map.of(
                                "op", "SET_NODE_FIELD",
                                "nodeId", "process:1",
                                "path", "__proto__.polluted",
                                "value", true),
                        Map.of(
                                "op", "ACTIVATE_WORKFLOW",
                                "workflowId", 9))));
        ToolCall call = ToolCall.of("preview-1", tool.getToolName(), arguments);

        Map<String, Object> envelope = objectMapper.readValue(
                tool.preview(call, Map.of("factoryId", "F006")), new TypeReference<>() {});
        Map<String, Object> data = readMap(envelope.get("data"));
        List<Map<String, Object>> patches = readListOfMaps(data.get("patches"));

        assertTrue((Boolean) envelope.get("success"));
        assertEquals(1, patches.size());
        assertEquals("conversionRule.mode", patches.get(0).get("path"));
        assertEquals(false, data.get("applied"));
    }

    @Test
    void previewRejectsMalformedNodeAndEdgeShapes() throws Exception {
        Map<String, Object> validNode = Map.of(
                "id", "process:1",
                "kind", "PROCESS",
                "position", Map.of("x", 16, "y", 32),
                "data", Map.of(
                        "workProcessId", "WP-1",
                        "processName", "Cut",
                        "inputUnit", "kg",
                        "outputUnit", "kg",
                        "ports", List.of(),
                        "conversionRule", Map.of("mode", "ACTUAL_WEIGHT"),
                        "reportingRequired", true));
        Map<String, Object> validEdge = Map.of(
                "id", "edge:1",
                "source", "material:1",
                "sourceHandle", "output",
                "target", "process:1",
                "targetHandle", "input:1");
        Map<String, Object> malformedProcessNode = Map.of(
                "id", "process:bad-port",
                "kind", "PROCESS",
                "position", Map.of("x", 16, "y", 32),
                "data", Map.of(
                        "workProcessId", "WP-1",
                        "processName", "Cut",
                        "inputUnit", "kg",
                        "outputUnit", "kg",
                        "ports", List.of(Map.of(
                                "id", "input:1",
                                "direction", "INPUT",
                                "materialNodeId", Map.of("execute", true),
                                "unit", "kg",
                                "ordinal", 0)),
                        "conversionRule", Map.of("mode", "ACTUAL_WEIGHT"),
                        "reportingRequired", true));
        String arguments = objectMapper.writeValueAsString(Map.of(
                "patches", List.of(
                        Map.of("op", "UPSERT_NODE", "node", validNode),
                        Map.of("op", "UPSERT_NODE", "node", malformedProcessNode),
                        Map.of("op", "UPSERT_NODE", "node", Map.of(
                                "id", "bad", "kind", "REPORT", "position", Map.of("x", 0, "y", 0),
                                "data", Map.of("name", "bad", "skuId", "bad"))),
                        Map.of("op", "UPSERT_EDGE", "edge", validEdge),
                        Map.of("op", "UPSERT_EDGE", "edge", Map.of(
                                "id", "bad-edge", "source", "a", "target", "b")),
                        Map.of("op", "REMOVE_NODE", "nodeId", "process:1", "execute", true))));

        Map<String, Object> envelope = objectMapper.readValue(
                tool.preview(
                        ToolCall.of("preview-shapes", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
        List<Map<String, Object>> patches = readListOfMaps(readMap(envelope.get("data")).get("patches"));

        assertEquals(List.of("UPSERT_NODE", "UPSERT_EDGE"),
                patches.stream().map(patch -> String.valueOf(patch.get("op"))).toList());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readListOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
