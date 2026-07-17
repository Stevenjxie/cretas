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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void workflowModuleReturnsValidatedSpecAndNeverExecutes() throws Exception {
        CanvasAIController.AIRequest request = workflowRequest();

        when(toolRegistry.getExecutor(TOOL_NAME)).thenReturn(Optional.of(workflowTool));
        when(dashScopeClient.chatLowTemp(any(String.class), eq("Change the conversion rule")))
                .thenReturn("{\"steps\":[{\"processName\":\"分切\",\"outputType\":\"SEMI_FINISHED\"}]}");

        ApiResponse<CanvasAIController.AIResponse> envelope =
                controller.chat("F006", null, request);

        CanvasAIController.AIResponse response = envelope.getData();
        assertFalse(response.isApplied());
        assertEquals(1, response.getDiffs().size());
        assertEquals("PRODUCT_PROCESS_WORKFLOW_SPEC", response.getDiffs().get(0).get("type"));
        Map<String, Object> diffParams = readMap(response.getDiffs().get(0).get("params"));
        Map<String, Object> spec = readMap(diffParams.get("spec"));
        assertEquals(1, ((List<?>) spec.get("steps")).size());
        verify(workflowTool, never()).preview(any(ToolCall.class), anyMap());
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void invalidWorkflowSpecReturnsNoDiffAndNeverExecutes() throws Exception {
        CanvasAIController.AIRequest request = workflowRequest();
        when(toolRegistry.getExecutor(TOOL_NAME)).thenReturn(Optional.of(workflowTool));
        when(dashScopeClient.chatLowTemp(any(String.class), any(String.class)))
                .thenReturn("{\"steps\":[]}");

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertFalse(response.isApplied());
        assertTrue(response.getDiffs().isEmpty());
        assertTrue(response.getReply().contains("未能从描述里解析"));
        verify(workflowTool, never()).preview(any(ToolCall.class), anyMap());
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
        assertEquals("AI 未能从描述里解析出可用的工序步骤，请把每一步的工序名和产出说清楚后重试", response.getReply());
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
    void workProcessCatalogAutopilotOnlyPreviewsUntilApplyIsExplicitlyConfirmed() throws Exception {
        String catalogTool = "canvas_work_process_catalog";
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("work_process_catalog");
        request.setMode("autopilot");
        request.setMessage("新增腌制工序");

        when(toolRegistry.getExecutor(catalogTool)).thenReturn(Optional.of(workflowTool));
        when(workflowTool.supportsPreview()).thenReturn(true);
        when(workflowTool.preview(any(ToolCall.class), anyMap())).thenReturn(objectMapper.writeValueAsString(Map.of(
                "success", true,
                "data", Map.of("action", "create", "processName", "腌制工序", "message", "已生成预览"))));

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertFalse(response.isApplied());
        assertEquals(1, response.getDiffs().size());
        assertTrue(response.getReply().contains("首次仅预览"));
        verify(workflowTool).preview(any(ToolCall.class), anyMap());
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void workProcessCatalogActionModeOnlyAnalyzesAndDoesNotExposeAnApplyDiff() throws Exception {
        String catalogTool = "canvas_work_process_catalog";
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("work_process_catalog");
        request.setMode("action");
        request.setMessage("新增腌制工序有什么影响");

        when(toolRegistry.getExecutor(catalogTool)).thenReturn(Optional.of(workflowTool));
        when(workflowTool.supportsPreview()).thenReturn(true);
        when(workflowTool.preview(any(ToolCall.class), anyMap())).thenReturn(objectMapper.writeValueAsString(Map.of(
                "success", true,
                "data", Map.of("message", "影响分析已生成"))));

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertFalse(response.isApplied());
        assertTrue(response.getDiffs().isEmpty());
        assertTrue(response.getReply().contains("仅分析"));
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void productWorkProcessToolWithoutPreviewIsRejectedWithoutAWrite() throws Exception {
        String productWorkProcessTool = "canvas_product_work_process_config";
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("product_work_process_config");
        request.setMode("autopilot");
        request.setMessage("配置工序");

        when(toolRegistry.getExecutor(productWorkProcessTool)).thenReturn(Optional.of(workflowTool));
        when(workflowTool.supportsPreview()).thenReturn(false);

        CanvasAIController.AIResponse response = controller.chat("F006", null, request).getData();

        assertFalse(response.isApplied());
        assertTrue(response.getReply().contains("不支持无写入预览"));
        verify(workflowTool, never()).execute(any(ToolCall.class), anyMap());
        verify(workflowTool, never()).preview(any(ToolCall.class), anyMap());
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
                .thenReturn("{\"steps\":[{\"processName\":\"分切\",\"outputType\":\"SEMI_FINISHED\"}]}");

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
