package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.MobileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanvasAIWorkflowConfigTest {

    private static final String TOOL_NAME = "canvas_product_process_workflow_config";

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private DashScopeClient dashScopeClient;
    @Mock
    private MobileService mobileService;
    @Mock
    private ToolExecutor workflowTool;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CanvasAIController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasAIController(toolRegistry, objectMapper, dashScopeClient, mobileService);
    }

    @Test
    void workflowModuleUsesLowTemperaturePreviewAndNeverExecutes() throws Exception {
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("product_process_workflow_config");
        request.setMode("autopilot");
        request.setMessage("Change the conversion rule");
        request.setParams(Map.of("context", Map.of(
                "definition", Map.of(
                        "schemaVersion", 1,
                        "status", "DRAFT",
                        "version", 1,
                        "nodes", List.of(),
                        "edges", List.of(),
                        "viewport", Map.of("x", 0, "y", 0, "zoom", 1)),
                "selectedNodeId", "process:1")));

        when(toolRegistry.getExecutor(TOOL_NAME)).thenReturn(Optional.of(workflowTool));
        when(dashScopeClient.chatLowTemp(
                argThat(prompt -> prompt.contains("WorkflowPatch")
                        && prompt.contains("\"schemaVersion\":1")
                        && prompt.contains("process:1")),
                eq("Change the conversion rule")))
                .thenReturn("```json\n[{\"op\":\"SET_NODE_FIELD\",\"nodeId\":\"process:1\","
                        + "\"path\":\"conversionRule.mode\",\"value\":\"SUM_OUTPUTS\"}]\n```");
        when(workflowTool.preview(org.mockito.ArgumentMatchers.any(ToolCall.class),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "success", true,
                        "data", Map.of(
                                "status", "PREVIEW",
                                "applied", false,
                                "patches", List.of(Map.of(
                                        "op", "SET_NODE_FIELD",
                                        "nodeId", "process:1",
                                        "path", "conversionRule.mode",
                                        "value", "SUM_OUTPUTS"))))));

        ApiResponse<CanvasAIController.AIResponse> envelope =
                controller.chat("F006", null, request);

        CanvasAIController.AIResponse response = envelope.getData();
        assertFalse(response.isApplied());
        assertEquals(1, response.getDiffs().size());
        assertEquals("PRODUCT_PROCESS_WORKFLOW_PATCH", response.getDiffs().get(0).get("type"));
        Map<String, Object> diffParams = readMap(response.getDiffs().get(0).get("params"));
        assertEquals(1, ((List<?>) diffParams.get("patches")).size());

        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        verify(workflowTool).preview(callCaptor.capture(), org.mockito.ArgumentMatchers.anyMap());
        verify(workflowTool, never()).execute(
                org.mockito.ArgumentMatchers.any(ToolCall.class),
                org.mockito.ArgumentMatchers.anyMap());

        Map<String, Object> toolArguments = objectMapper.readValue(
                callCaptor.getValue().getFunction().getArguments(), new TypeReference<>() {});
        assertTrue(toolArguments.containsKey("definition"));
        assertEquals("process:1", toolArguments.get("selectedNodeId"));
        assertEquals("Change the conversion rule", toolArguments.get("message"));
        assertEquals(1, ((List<?>) toolArguments.get("patches")).size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        return (Map<String, Object>) value;
    }
}
