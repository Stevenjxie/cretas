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
        // 本类测的是【开关打开后】的落库行为, 所以传 true。
        // 「开关关着」那一档由 writeDisabledByDefaultNeverTouchesTheDatabase 单独覆盖。
        tool = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator(), workflowService, true);
    }

    @Test
    @DisplayName("🔴 承重: 补丁落到 saveDraft, 且 factoryId 来自 context 不是 AI 入参")
    void validPatchIsWrittenToDraftWithFactoryIdFromContext() throws Exception {
        ProductProcessWorkflowDTO saved = new ProductProcessWorkflowDTO();
        // ⚠️ 桩必须带 status: 工具现在按 saved 的【真实状态】判有没有真写进去
        // (saveDraft 有一条不建草稿的早退路径)。桩不设 status 会被正确地判成「没写」。
        saved.setStatus("DRAFT");
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
        // ⚠️ 桩必须带 status: 工具现在按 saved 的【真实状态】判有没有真写进去
        // (saveDraft 有一条不建草稿的早退路径)。桩不设 status 会被正确地判成「没写」。
        saved.setStatus("DRAFT");
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
    @DisplayName("🔴 承重: 改工序名的 UPSERT_NODE 【不许】顺手抹掉那道工序的调料克数")
    void upsertNodeMustNotSilentlyDropExistingMaterialBindings() throws Exception {
        // 🔴 这条是整支审查抓出的 Critical。链条:
        //   1. 想改工序名【只能】用 UPSERT_NODE —— PROCESS 节点的 SET_NODE_FIELD 不允许 name
        //   2. UPSERT_NODE 的清洗器 PROCESS_DATA_KEYS 【不含】materialBindings
        //      -> agent 想让补丁通过, 必须把调料字段拿掉
        //   3. applyNodePatch 的 UPSERT 是【整节点替换】(set(indexOf(existing), next)), 不继承字段
        //   4. 分流闸只看补丁的 op/path, UPSERT_NODE 不在它名单里 -> 放行
        // 合起来: 「把卤制改个名」会静默清空那道工序的全部调料克数, 且返回 applied:true。
        // 方向正是那条「约束 4」注释最担心的「直接影响扣料与成本」。
        ProductProcessWorkflowDTO stored = objectMapper.convertValue(
                definitionWithBinding("PT-001", 3L), ProductProcessWorkflowDTO.class);
        when(workflowService.getEditorDefinition("F006", "PT-001"))
                .thenReturn(java.util.Optional.of(stored));

        // agent 重发的 definition 与补丁里都【没有】materialBindings —— 它就是这么被迫写的
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definitionWithOwner("PT-001", 3L),
                "patches", List.of(Map.of("op", "UPSERT_NODE", "node", Map.of(
                        "id", "process:1", "kind", "PROCESS",
                        "position", Map.of("x", 256, "y", 32),
                        "data", Map.of(
                                "workProcessId", "WP-1",
                                "processName", "卤制(改名)",
                                "inputUnit", "kg",
                                "outputUnit", "kg",
                                "reportingRequired", true,
                                "conversionRule", Map.of("mode", "ACTUAL_WEIGHT"),
                                "ports", List.of(
                                        Map.of("id", "input:1", "direction", "INPUT",
                                                "materialNodeId", "raw", "materialKind", "RAW_MATERIAL",
                                                "unit", "kg", "ordinal", 0),
                                        Map.of("id", "output:1", "direction", "OUTPUT",
                                                "materialNodeId", "semi", "materialKind", "SEMI_FINISHED",
                                                "unit", "kg", "ordinal", 0))))))));

        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(ToolCall.of("rename", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        // ⛔ 必须拒。放行等于让「改名」变成「删调料」。
        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertEquals("WORKFLOW_AI_PREVIEW_ONLY", envelope.get("errorCode"));
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
        // ⚠️ 原来只断 assertNotNull(errorCode) —— buildSemanticError 的【每个】分支都带
        // errorCode, 所以把 catch(BusinessException) 整块删掉(409 落到 catch(Exception)
        // 变成 WORKFLOW_PATCH_FAILED)那条断言照样绿。「冲突不吞」被列为承重项却无人守。
        // 现在断具体错误码与消息透传, 才真正钉住「不吞」。
        assertEquals("PRODUCT_PROCESS_WORKFLOW_CONFLICT", envelope.get("errorCode"));
        assertTrue(String.valueOf(envelope.get("message")).contains("已被其他人更新")
                || String.valueOf(envelope.get("error")).contains("已被其他人更新"));
    }

    @Test
    @DisplayName("🔴 承重: saveDraft 走「不建草稿」早退路径时, ⛔ 不许报 applied=true")
    void unchangedGraphIsNotReportedAsWritten() throws Exception {
        // saveDraft 有一条早退: 无草稿 + 有已发布 + 图相同 -> 直接返回【已发布那行】,
        // 一个字节都没写(ProductProcessWorkflowServiceImpl:93-99)。
        // 工具若硬编码 status="DRAFT"/applied=true, 就把「库里一行没动」报成「已写入草稿」,
        // 用户去画布找草稿会找不到 —— 那是把「没写成」伪装成「写成了」。
        ProductProcessWorkflowDTO published = new ProductProcessWorkflowDTO();
        published.setStatus("PUBLISHED");
        published.setLockVersion(4L);
        when(workflowService.saveDraft(any(), any(), any())).thenReturn(published);

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner("PT-001", 3L));

        assertTrue((Boolean) envelope.get("success"));
        Map<String, Object> data = asMap(envelope.get("data"));
        assertEquals(Boolean.FALSE, data.get("applied"));
        assertEquals("PUBLISHED", data.get("status"));
    }

    @Test
    @DisplayName("🔴 承重: 开关【默认关】时一次库都不碰 —— 这是上线时的实际形态")
    void writeDisabledByDefaultNeverTouchesTheDatabase() throws Exception {
        // ⛔ 只测「开着」那一档等于没测默认行为 —— prod 上线时开关是关的,
        // 那一档才是真正会跑的代码。
        ToolExecutor disabled = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator(), workflowService, false);

        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definitionWithOwner("PT-001", 3L),
                "patches", List.of(Map.of("op", "SET_NODE_FIELD",
                        "nodeId", "raw", "path", "name", "value", "改过的原料名"))));

        Map<String, Object> envelope = objectMapper.readValue(
                disabled.execute(ToolCall.of("off", disabled.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertEquals("WORKFLOW_AI_PREVIEW_ONLY", envelope.get("errorCode"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 承重: 提交别的产品的图 -> 拒, ⛔ 不许给那个产品新建一张别人的草稿")
    void graphFromAnotherProductIsRejected() throws Exception {
        // 决定「覆写哪张画布」的 productTypeId 来自 AI 可控的 definition, 而 context 里
        // 没有 productTypeId 可比对。模型把它填成同厂另一个产品时, requireWorkflowOwner
        // 会放行(那确实是本厂产品) -> 给【那个】产品新建一张内容是【别人】的草稿。
        //
        // ⚠️ 这个洞只在【新建草稿】那一支: 目标产品已有草稿时 assertCurrentVersion 会 409。
        // 判据: 图的身份来自【节点 id】—— 节点 id 跟着图走, 别的产品的图一个都对不上。
        ProductProcessWorkflowDTO otherProduct = objectMapper.convertValue(
                definitionWithOtherNodeIds(), ProductProcessWorkflowDTO.class);
        when(workflowService.getEditorDefinition("F006", "PT-001"))
                .thenReturn(java.util.Optional.of(otherProduct));

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner("PT-001", 3L));

        assertEquals(Boolean.FALSE, envelope.get("success"));
        assertEquals("WORKFLOW_OWNER_MISMATCH", envelope.get("errorCode"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
    }

    @Test
    @DisplayName("库里还没有图(新产品第一张草稿) -> 放行, ⛔ 别把闸做成「新产品永远建不了」")
    void firstDraftOfBrandNewProductIsAllowed() throws Exception {
        ProductProcessWorkflowDTO saved = new ProductProcessWorkflowDTO();
        saved.setStatus("DRAFT");
        saved.setLockVersion(1L);
        when(workflowService.getEditorDefinition(any(), any())).thenReturn(java.util.Optional.empty());
        when(workflowService.saveDraft(any(), any(), any())).thenReturn(saved);

        Map<String, Object> envelope = execute(
                Map.of("factoryId", "F006"), definitionWithOwner("PT-NEW", 3L));

        assertTrue((Boolean) envelope.get("success"));
        verify(workflowService).saveDraft(eq("F006"), eq("PT-NEW"), any());
    }

    @Test
    @DisplayName("🔴 承重: 每一条【明确拒绝】都要带 status=DECLINED —— 否则网关记成疑似写入")
    void everyDeclineCarriesTheDeclinedStatusForTheGateway() throws Exception {
        // 网关(DefaultToolExecutionGateway)对 success:false 只认两种干净失败:
        // NEED_MORE_INFO 与 DECLINED。不带 status 的一律 -> OUTCOME_UNKNOWN,
        // 台账记 IN_DOUBT 且【payload 被清空】—— 于是「涉及克数只能预览」这句话
        // 传不到用户那里, 用户看到的是「执行结果需要人工对账」。
        // 一个结构上【确定没有写入】的拒绝, 被记成需要人工对账的脏账。

        // (a) 开关关着
        ToolExecutor disabled = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator(), workflowService, false);
        assertEquals("DECLINED", declineStatus(disabled, definitionWithOwner("PT-001", 3L)));

        // (b) 克数补丁
        String bomArgs = objectMapper.writeValueAsString(Map.of(
                "definition", definitionWithOwner("PT-001", 3L),
                "patches", List.of(Map.of("op", "SET_NODE_FIELD",
                        "nodeId", "process:1", "path", "materialBindings",
                        "value", List.of(Map.of("materialTypeId", "RMT-1", "dosagePerKgG", 12.5d))))));
        Map<String, Object> bomEnvelope = objectMapper.readValue(
                tool.execute(ToolCall.of("bom", tool.getToolName(), bomArgs),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
        assertEquals("DECLINED", bomEnvelope.get("status"));

        // (c) 图不属于这个产品
        ProductProcessWorkflowDTO otherProduct = objectMapper.convertValue(
                definitionWithOtherNodeIds(), ProductProcessWorkflowDTO.class);
        when(workflowService.getEditorDefinition("F006", "PT-001"))
                .thenReturn(java.util.Optional.of(otherProduct));
        Map<String, Object> mismatch = execute(
                Map.of("factoryId", "F006"), definitionWithOwner("PT-001", 3L));
        assertEquals("DECLINED", mismatch.get("status"));
    }

    private String declineStatus(ToolExecutor executor, Map<String, Object> definition)
            throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definition,
                "patches", List.of(Map.of("op", "SET_NODE_FIELD",
                        "nodeId", "raw", "path", "name", "value", "改名"))));
        Map<String, Object> envelope = objectMapper.readValue(
                executor.execute(ToolCall.of("d", executor.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
        return String.valueOf(envelope.get("status"));
    }

    @Test
    @DisplayName("context 里没有 factoryId -> 拒, 且【不许】回退到入参里的任何值")
    void missingFactoryIdInContextIsRejected() throws Exception {
        // M1: 原来只覆盖「不许从入参取」, 没覆盖「context 压根没有」这一路。
        Map<String, Object> envelope = execute(Map.of(), definitionWithOwner("PT-001", 3L));

        assertEquals(Boolean.FALSE, envelope.get("success"));
        verify(workflowService, never()).saveDraft(any(), any(), any());
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

    /** 带一行【已存在】调料绑定的定义 —— 代表库里的真实状态。 */
    /** 一张【别的产品】的图: 节点 id 与 definitionWithOwner 完全不重叠。 */
    private Map<String, Object> definitionWithOtherNodeIds() {
        Map<String, Object> definition =
                new LinkedHashMap<>(definitionWithOwner("PT-001", 9L));
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (Object raw : (List<?>) definition.get("nodes")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) raw);
            node.put("id", "other-" + node.get("id"));
            nodes.add(node);
        }
        definition.put("nodes", nodes);
        definition.put("edges", List.of());
        return definition;
    }

    private Map<String, Object> definitionWithBinding(String productTypeId, Long lockVersion) {
        Map<String, Object> definition =
                new LinkedHashMap<>(definitionWithOwner(productTypeId, lockVersion));
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (Object raw : (List<?>) definition.get("nodes")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) raw);
            if ("process:1".equals(node.get("id"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) node.get("data"));
                data.put("materialBindings", List.of(
                        Map.of("materialTypeId", "RMT-1", "dosagePerKgG", 12.5d)));
                node.put("data", data);
            }
            nodes.add(node);
        }
        definition.put("nodes", nodes);
        return definition;
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
