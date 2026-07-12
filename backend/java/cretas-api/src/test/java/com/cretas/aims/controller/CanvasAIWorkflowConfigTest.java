package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.workprocess.ProductProcessWorkflowConfigTool;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
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
        CanvasAIController.AIRequest request = workflowRequest();

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

    @Test
    void rejectedMixedBatchReturnsNoDiffAndNeverExecutes() throws Exception {
        CanvasAIController.AIRequest request = workflowRequest();
        when(toolRegistry.getExecutor(TOOL_NAME)).thenReturn(Optional.of(workflowTool));
        when(dashScopeClient.chatLowTemp(any(String.class), any(String.class)))
                .thenReturn("[{\"op\":\"SET_NODE_FIELD\",\"nodeId\":\"process:1\","
                        + "\"path\":\"conversionRule.mode\",\"value\":\"SUM_OUTPUTS\"},"
                        + "{\"op\":\"ACTIVATE_WORKFLOW\",\"workflowId\":9}]");
        when(workflowTool.preview(any(ToolCall.class), anyMap()))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "success", false,
                        "errorCode", "WORKFLOW_PATCH_REJECTED",
                        "error", "Workflow patch batch rejected")));

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertFalse(response.isApplied());
        assertTrue(response.getDiffs().isEmpty());
        assertTrue(response.getReply().contains("rejected"));
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"op\":\"SET_NODE_FIELD\"}", "[{not-json]"})
    void emptyNonArrayOrMalformedLlmOutputReturnsSafeNoDiffResponse(String llmOutput) throws Exception {
        when(toolRegistry.getExecutor(TOOL_NAME)).thenReturn(Optional.of(workflowTool));
        when(dashScopeClient.chatLowTemp(any(String.class), any(String.class)))
                .thenReturn(llmOutput);

        CanvasAIController.AIResponse response =
                controller.chat("F006", null, workflowRequest()).getData();

        assertFalse(response.isApplied());
        assertTrue(response.getDiffs().isEmpty());
        assertEquals("AI 未返回可审核的 Workflow 补丁，请调整描述后重试", response.getReply());
        verify(workflowTool, never()).preview(any(ToolCall.class), anyMap());
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void applyDiffsHardRejectsWorkflowPreviewToolWithoutExecute() throws Exception {
        ApiResponse<String> response = controller.applyDiffs(
                "F006",
                null,
                List.of(Map.of(
                        "tool", TOOL_NAME,
                        "params", Map.of("patches", List.of()))));

        assertTrue(response.getData().contains("0/1"));
        assertTrue(response.getData().contains("WORKFLOW_AI_PREVIEW_ONLY"));
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
        verify(toolRegistry, never()).getExecutor(TOOL_NAME);
    }

    @Test
    void realRegistryResolvesRealWorkflowToolForControllerPreview() throws Exception {
        ProductProcessWorkflowConfigTool realTool = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator());
        ToolRegistry realRegistry = new ToolRegistry();
        Field executors = ToolRegistry.class.getDeclaredField("toolExecutors");
        executors.setAccessible(true);
        executors.set(realRegistry, List.of(realTool));
        Field similarityService = ToolRegistry.class.getDeclaredField("toolSimilarityService");
        similarityService.setAccessible(true);
        similarityService.set(realRegistry, mock(ToolSimilarityService.class));
        realRegistry.init();
        CanvasAIController realController = new CanvasAIController(
                realRegistry, objectMapper, dashScopeClient, mobileService);
        when(dashScopeClient.chatLowTemp(any(String.class), any(String.class)))
                .thenReturn("[{\"op\":\"UPSERT_NODE\",\"node\":{"
                        + "\"id\":\"raw\",\"kind\":\"RAW_MATERIAL\","
                        + "\"position\":{\"x\":16,\"y\":32},"
                        + "\"data\":{\"name\":\"Raw\",\"skuId\":\"RM-1\"}}}]");

        CanvasAIController.AIResponse response =
                realController.chat("F006", null, workflowRequest()).getData();

        assertTrue(realRegistry.getExecutor(TOOL_NAME).isPresent());
        assertEquals(ProductProcessWorkflowConfigTool.class,
                realRegistry.getExecutor(TOOL_NAME).orElseThrow().getClass());
        assertFalse(response.isApplied());
        assertEquals(1, response.getDiffs().size());
    }

    private CanvasAIController.AIRequest workflowRequest() {
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
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        return (Map<String, Object>) value;
    }
}
