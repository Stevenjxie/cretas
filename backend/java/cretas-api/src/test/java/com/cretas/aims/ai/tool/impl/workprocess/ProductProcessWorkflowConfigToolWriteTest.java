package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布补丁的<b>落库</b>路径。
 *
 * <p>⛔ 这里不断言措辞。断言的是三件能被证伪的事：
 * 写的是不是草稿、factoryId 从哪来、校验不过时有没有碰库。
 */
class ProductProcessWorkflowConfigToolWriteTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProductProcessWorkflowService workflowService =
            mock(ProductProcessWorkflowService.class);
    private ToolExecutor tool;

    @BeforeEach
    void setUp() {
        tool = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator(), workflowService);
    }

    @Test
    @DisplayName("🔴 承重: 补丁落到 saveDraft, 且 factoryId 来自 context 不是 AI 入参")
    void validPatchIsWrittenToDraftWithFactoryIdFromContext() throws Exception {
        ProductProcessWorkflowDTO saved = new ProductProcessWorkflowDTO();
        saved.setLockVersion(7L);
        when(workflowService.saveDraft(any(), any(), any())).thenReturn(saved);

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"),
                definitionWithOwner("PT-001", 3L));

        ArgumentCaptor<String> factoryId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> productTypeId = ArgumentCaptor.forClass(String.class);
        verify(workflowService).saveDraft(
                factoryId.capture(), productTypeId.capture(), any());
        assertEquals("F006", factoryId.getValue());
        assertEquals("PT-001", productTypeId.getValue());

        assertTrue((Boolean) envelope.get("success"));
        Map<String, Object> data = asMap(envelope.get("data"));
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("DRAFT", data.get("status"));
        // 回传新 lockVersion, 下一次补丁才接得上; 不回传的话 agent 只能改一次。
        assertEquals(7, ((Number) data.get("lockVersion")).intValue());
    }

    @Test
    @DisplayName("🔴 承重: AI 入参里的 factoryId 【不得】覆盖 context 的")
    void factoryIdInArgumentsIsIgnored() throws Exception {
        ProductProcessWorkflowDTO saved = new ProductProcessWorkflowDTO();
        saved.setLockVersion(1L);
        when(workflowService.saveDraft(any(), any(), any())).thenReturn(saved);

        Map<String, Object> definition = new LinkedHashMap<>(definitionWithOwner("PT-001", 3L));
        definition.put("factoryId", "OTHER_TENANT");

        execute(Map.of("factoryId", "F006"), definition);

        verify(workflowService).saveDraft(eq("F006"), any(), any());
        verify(workflowService, never()).saveDraft(eq("OTHER_TENANT"), any(), any());
    }

    @Test
    @DisplayName("🔴 承重: 校验不过时【一次库都不碰】")
    void rejectedPatchNeverTouchesTheDatabase() throws Exception {
        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(
                        ToolCall.of("bad", tool.getToolName(), objectMapper.writeValueAsString(
                                Map.of("definition", definitionWithOwner("PT-001", 3L),
                                        "patches", List.of(Map.of(
                                                "op", "ACTIVATE_WORKFLOW", "workflowId", 9))))),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        assertEquals(Boolean.FALSE, envelope.get("success"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 承重: 补丁【通过清洗但校验不过】时也不许落库 —— 校验必须在写之前")
    void patchThatPassesSanitizationButFailsValidationNeverReachesTheDatabase() throws Exception {
        // ⚠️ 这条是变异测试逼出来的。原来只有 rejectedPatchNeverTouchesTheDatabase 一条,
        // 它用的坏补丁(op=ACTIVATE_WORKFLOW)【被清洗器提前拒了】, 根本走不到 validateForDraft。
        // 结果是: 把 validateForDraft 整行掐掉, 那条断言照样绿 —— 「校验先于落库」无人守。
        //
        // ⚠️ 第一版探针用的是 REMOVE_NODE, 结果在正常代码上就红了 ——
        // applyNodePatch 的 REMOVE 分支会【级联删掉连边】(:582), 留下的是合法小图,
        // 所以它本来就该落库。那是【我的探针选错了】, 不是实现有问题。
        //
        // 换成 UPSERT_EDGE 指向一个不存在的节点: sanitizeEdgePatch(:403) 只做词法检查
        // (五个键非空即放行), 不验节点存在; 而 validateForDraft 会拒
        // 「连线引用了不存在的 Cell」。这条路径才真正只被 validateForDraft 挡住。
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definitionWithOwner("PT-001", 3L),
                "patches", List.of(Map.of("op", "UPSERT_EDGE", "edge", Map.of(
                        "id", "e-dangling",
                        "source", "process:1",
                        "sourceHandle", "output:1",
                        "target", "node-that-does-not-exist",
                        "targetHandle", "input")))));

        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(ToolCall.of("dangling", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        assertEquals(Boolean.FALSE, envelope.get("success"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("productTypeId 缺失 -> 明确报错, ⛔ 不猜一个")
    void missingProductTypeIdIsRejectedRatherThanGuessed() throws Exception {
        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner(null, 3L));

        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertEquals("WORKFLOW_OWNER_REQUIRED", envelope.get("errorCode"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("saveDraft 抛 409(草稿被别人改过) -> 如实透出, ⛔ 不吞不重试")
    void draftConflictIsReportedNotSwallowed() throws Exception {
        when(workflowService.saveDraft(any(), any(), any()))
                .thenThrow(new com.cretas.aims.exception.BusinessException(409, "该 Workflow 已被其他人更新")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT"));

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner("PT-001", 3L));

        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertNotNull(envelope.get("errorCode"));
    }

    private Map<String, Object> execute(
            Map<String, Object> context, Map<String, Object> definition) throws Exception {
        // ⚠️ 纯拓扑补丁: "name" 只对物料 Cell 合法(isPathCompatibleWithNodeKind,
        // Task 1 既有闸, 本任务不碰) —— 目标是 "raw"(RAW_MATERIAL), 不是 "process:1"。
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definition,
                "patches", List.of(Map.of(
                        "op", "SET_NODE_FIELD",
                        "nodeId", "raw",
                        "path", "name",
                        "value", "改过的原料名"))));
        return objectMapper.readValue(
                tool.execute(ToolCall.of("exec", tool.getToolName(), arguments), context),
                new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private Map<String, Object> definitionWithOwner(String productTypeId, Long lockVersion) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaVersion", 1);
        definition.put("status", "DRAFT");
        definition.put("version", 1);
        if (productTypeId != null) {
            definition.put("productTypeId", productTypeId);
        }
        definition.put("lockVersion", lockVersion);
        // ⚠️ 每个 Cell 必须带 position —— ProductProcessWorkflowValidator#validateForDraft
        // 逐条校验 "Cell 必须包含画布坐标", 缺了这一项 buildValidatedCandidate 在到达
        // saveDraft 之前就会被拒(与本任务无关的既有闸, 不是我在测的东西)。
        definition.put("nodes", List.of(
                Map.of("id", "raw", "kind", "RAW_MATERIAL",
                        "position", Map.of("x", 0, "y", 0),
                        "data", Map.of("name", "原料", "baseUnit", "kg")),
                Map.of("id", "process:1", "kind", "PROCESS",
                        "position", Map.of("x", 200, "y", 0),
                        "data", Map.of("processName", "工序一", "inputUnit", "kg", "outputUnit", "kg",
                                "ports", List.of(
                                        Map.of("id", "input:1", "direction", "INPUT",
                                                "materialNodeId", "raw", "materialKind", "RAW_MATERIAL",
                                                "unit", "kg", "ordinal", 0),
                                        Map.of("id", "output:1", "direction", "OUTPUT",
                                                "materialNodeId", "semi", "materialKind", "SEMI_FINISHED",
                                                "unit", "kg", "ordinal", 0)))),
                Map.of("id", "semi", "kind", "SEMI_FINISHED",
                        "position", Map.of("x", 400, "y", 0),
                        "data", Map.of("name", "半成品", "baseUnit", "kg"))));
        definition.put("edges", List.of(
                Map.of("id", "e1", "source", "raw", "sourceHandle", "output",
                        "target", "process:1", "targetHandle", "input:1"),
                Map.of("id", "e2", "source", "process:1", "sourceHandle", "output:1",
                        "target", "semi", "targetHandle", "input")));
        definition.put("viewport", Map.of("x", 0, "y", 0, "zoom", 1));
        return definition;
    }
}
