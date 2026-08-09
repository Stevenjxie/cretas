package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.constant.SeasoningProcessCategory;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画布 AI 扩能到 BOM 字段（2026-08-07 阶段 4）的服务端校验。
 *
 * <p>设计定稿列了<b>五条硬约束</b>，这里每条至少一个反例 —— 反例才是闸的价值所在，
 * 只测正例的闸对"约束根本没生效"完全沉默。
 *
 * <ol>
 *   <li>类别闸：往非熟制工序写锅序比例、往非注射工序写注射量，一律拒绝并给可读原因</li>
 *   <li>数值域：用量 &gt; 0，锅序 0–100，注射量 &gt; 0。<b>越界拒绝，不截断</b></li>
 *   <li>materialTypeId 必须存在，AI 不许凭空造物料</li>
 *   <li>审核弹窗不得跳过</li>
 *   <li>补丁路与「确定性编译器」路两条都要覆盖</li>
 * </ol>
 */
class ProductProcessWorkflowConfigToolBomFieldsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolExecutor tool;

    @BeforeEach
    void setUp() {
        tool = new ProductProcessWorkflowConfigTool(
                objectMapper, new ProductProcessWorkflowValidator());
    }

    // ───────────────────────── 约束 1：类别闸 ─────────────────────────

    @Test
    void potRatioOnNonCookingProcessIsRejectedWithReadableReason() throws Exception {
        Map<String, Object> envelope = preview(
                definitionWithCategory("切配"),
                List.of(upsertBinding("process:1", binding("RMT-1", 12.5d, 60d))));

        assertFalse((Boolean) envelope.get("success"));
        assertEquals("WORKFLOW_PATCH_REJECTED", envelope.get("errorCode"));

        // ⛔ 不只是"被拒了"——必须说清为什么、以及怎么办。通用文案不算数：
        //    AI 改不动却不说原因，用户只会反复重试同一句话。
        String reason = String.valueOf(envelope.get("error"));
        assertTrue(reason.contains("后续锅调料比例"), "原因要点名是哪个参数: " + reason);
        assertTrue(reason.contains("切配"), "原因要点名当前类别: " + reason);
        assertTrue(reason.contains(SeasoningProcessCategory.COOKING), "原因要说明允许的类别: " + reason);
    }

    @Test
    void potRatioOnCookingProcessIsAccepted() throws Exception {
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(upsertBinding("process:1", binding("RMT-1", 12.5d, 60d))));

        // 阴性对照：同一条补丁，只把工序类别换成熟制就该放行。
        // 没有这条，上面那个"拒绝"可能只是因为补丁本身格式不对。
        assertTrue((Boolean) envelope.get("success"), String.valueOf(envelope.get("error")));
    }

    @Test
    void injectionAmountOnNonInjectionProcessIsRejectedWithReadableReason() throws Exception {
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(setField("process:1", "injectionAmount", 3.5d)));

        assertFalse((Boolean) envelope.get("success"));
        String reason = String.valueOf(envelope.get("error"));
        assertTrue(reason.contains("注射量"), reason);
        assertTrue(reason.contains(SeasoningProcessCategory.INJECTION), reason);
    }

    @Test
    void injectionAmountOnInjectionProcessIsAccepted() throws Exception {
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.INJECTION),
                List.of(setField("process:1", "injectionAmount", 3.5d)));

        assertTrue((Boolean) envelope.get("success"), String.valueOf(envelope.get("error")));
    }

    @Test
    void categoryGateAlsoAppliesToWholeTableReplacement() throws Exception {
        // 单行动作被闸住了，整表替换就不能是绕过去的后门。
        Map<String, Object> envelope = preview(
                definitionWithCategory("切配"),
                List.of(setField("process:1", "materialBindings",
                        List.of(binding("RMT-1", 12.5d, 60d)))));

        assertFalse((Boolean) envelope.get("success"));
        assertTrue(String.valueOf(envelope.get("error")).contains("后续锅调料比例"));
    }

    @Test
    void missingProcessCategoryIsTreatedAsNotAllowed() throws Exception {
        // 「没设类别」不等于「随便配」。缺证据时按拒绝处理（禁止降级）。
        Map<String, Object> envelope = preview(
                definitionWithCategory(null),
                List.of(upsertBinding("process:1", binding("RMT-1", 12.5d, 60d))));

        assertFalse((Boolean) envelope.get("success"));
        assertTrue(String.valueOf(envelope.get("error")).contains("未设置"));
    }

    // ───────────────────────── 约束 2：数值域 ─────────────────────────

    @Test
    void outOfRangeNumbersAreRejectedNotClamped() throws Exception {
        record Case(String label, Map<String, Object> patch) {
        }
        List<Case> cases = List.of(
                new Case("用量为 0", upsertBinding("process:1", binding("RMT-1", 0d, null))),
                new Case("用量为负", upsertBinding("process:1", binding("RMT-1", -1d, null))),
                new Case("锅序 > 100", upsertBinding("process:1", binding("RMT-1", 5d, 101d))),
                new Case("锅序为负", upsertBinding("process:1", binding("RMT-1", 5d, -0.1d))),
                new Case("注射量为 0", setField("process:1", "injectionAmount", 0d)),
                new Case("注射量为负", setField("process:1", "injectionAmount", -2d)),
                new Case("用量不是数字", upsertBinding("process:1", bindingRaw("RMT-1", "12.5", null))));

        for (Case testCase : cases) {
            Map<String, Object> envelope = preview(
                    definitionWithCategory(SeasoningProcessCategory.COOKING), List.of(testCase.patch()));
            assertFalse((Boolean) envelope.get("success"), "应被拒绝: " + testCase.label());
            // ⛔ 关键: 拒绝而不是"截断到边界后接受"。截断会把 AI 的错误值
            //    悄悄变成一个看起来合理的值存下去，扣料和成本跟着错且毫无痕迹。
            assertFalse(envelope.containsKey("data"), "不许截断后接受: " + testCase.label());
        }
    }

    @Test
    void potRatioZeroIsAcceptedBecauseItMeansSomething() throws Exception {
        // 0 在锅序上是**合法配置**（后续锅不再加这味调料），不能跟"越界"混为一谈。
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(upsertBinding("process:1", binding("RMT-1", 5d, 0d))));

        assertTrue((Boolean) envelope.get("success"), String.valueOf(envelope.get("error")));
    }

    // ────────────────── 约束 3：不许凭空造物料 ──────────────────

    @Test
    void bindingWithoutMaterialTypeIdIsRejected() throws Exception {
        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("materialName", "八角");
        noId.put("dosagePerKgG", 3d);

        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(Map.of("op", "UPSERT_MATERIAL_BINDING", "nodeId", "process:1", "binding", noId)));

        assertFalse((Boolean) envelope.get("success"));
    }

    @Test
    void bindingWithUnknownKeysIsRejected() throws Exception {
        Map<String, Object> smuggled = new LinkedHashMap<>();
        smuggled.put("materialTypeId", "RMT-1");
        smuggled.put("dosagePerKgG", 3d);
        smuggled.put("factoryId", "F001");   // 跨厂偷渡
        smuggled.put("price", 99d);

        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(Map.of("op", "UPSERT_MATERIAL_BINDING", "nodeId", "process:1", "binding", smuggled)));

        assertFalse((Boolean) envelope.get("success"));
    }

    @Test
    void materialBindingOnMaterialNodeIsRejected() throws Exception {
        // 投入挂工序。挂到物料节点上没有意义，也会绕开类别闸（物料节点没有 processCategory）。
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(upsertBinding("raw", binding("RMT-1", 3d, null))));

        assertFalse((Boolean) envelope.get("success"));
    }

    @Test
    void removingAnAbsentBindingIsRejectedRatherThanSilentlyIgnored() throws Exception {
        // 静默忽略会让 AI「以为删掉了」，用户也以为删掉了，而那行还在。
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(Map.of("op", "REMOVE_MATERIAL_BINDING",
                        "nodeId", "process:1", "materialTypeId", "RMT-NOT-THERE")));

        assertFalse((Boolean) envelope.get("success"));
    }

    // ────────────── 约束 4：审核弹窗不得跳过 ──────────────

    @Test
    void executeNeverAppliesCostBearingPatches() throws Exception {
        // 改克数比改拓扑风险高（直接影响扣料与成本），所以【克数补丁】结构上只能出预览。
        // 不是"前端记得弹审核框"，而是 execute 根本不写任何东西。
        //
        // ⚠️ 2026-08-09 这条断言被【加强】了: 它原来传的是空 payload "{}",
        // 那压根没测到 BOM 补丁 —— 换成任何拒绝理由(比如"缺 definition")都能让它绿。
        // 现在传一条【真的调料克数补丁】, 才真正测到上面那句注释说的事。
        // Steve 同日拍板: 拓扑补丁可落草稿, 克数仍拒 —— 分界收窄了, 但这条一个字节没让步。
        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(
                        ToolCall.of("apply", tool.getToolName(), objectMapper.writeValueAsString(
                                Map.of("definition", definitionWithCategory(SeasoningProcessCategory.COOKING),
                                        "patches", List.of(
                                                upsertBinding("process:1", binding("RMT-1", 12.5d, 60d)))))),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        assertFalse((Boolean) envelope.get("success"));
        assertEquals("WORKFLOW_AI_PREVIEW_ONLY", envelope.get("errorCode"));
    }

    @Test
    void executeNeverAppliesMaterialBindingRemoval() throws Exception {
        // 删一行调料同样动扣料 —— 增删两个方向都要拒, 不能只拒新增。
        Map<String, Object> envelope = objectMapper.readValue(
                tool.execute(
                        ToolCall.of("remove", tool.getToolName(), objectMapper.writeValueAsString(
                                Map.of("definition",
                                        definitionWithExistingBinding(SeasoningProcessCategory.COOKING),
                                        "patches", List.of(Map.of(
                                                "op", "REMOVE_MATERIAL_BINDING",
                                                "nodeId", "process:1",
                                                "materialTypeId", "RMT-1"))))),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});

        assertFalse((Boolean) envelope.get("success"));
        assertEquals("WORKFLOW_AI_PREVIEW_ONLY", envelope.get("errorCode"));
    }

    @Test
    void previewNeverReportsApplied() throws Exception {
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(upsertBinding("process:1", binding("RMT-1", 12.5d, 60d))));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertNotNull(data);
        assertEquals(Boolean.FALSE, data.get("applied"));
        assertEquals("PREVIEW", data.get("status"));
    }

    // ───────────── 正例：补丁真的把值写进了候选定义 ─────────────

    @Test
    void acceptedBindingIsActuallyWrittenIntoTheCandidate() throws Exception {
        // ⚠️ 「没报错」不等于「改成功了」。这条把返回的补丁内容也验一遍，
        //    否则一个把补丁原样丢弃的实现同样会让上面所有正例变绿。
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(upsertBinding("process:1", binding("RMT-1", 12.5d, 60d))));

        assertTrue((Boolean) envelope.get("success"), String.valueOf(envelope.get("error")));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patches = (List<Map<String, Object>>) data.get("patches");
        assertEquals(1, patches.size());
        assertEquals("UPSERT_MATERIAL_BINDING", patches.get(0).get("op"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echoed = (Map<String, Object>) patches.get(0).get("binding");
        assertEquals("RMT-1", echoed.get("materialTypeId"));
        assertEquals(12.5d, ((Number) echoed.get("dosagePerKgG")).doubleValue(), 1e-9);
        assertEquals(60d, ((Number) echoed.get("subsequentPotRatio")).doubleValue(), 1e-9);
    }

    @Test
    void byproductFlagOnOutputNodeIsAllowed() throws Exception {
        // 阶段 2 的副产是带标记的普通产出节点 —— AI 增删副产因此复用 UPSERT_NODE/EDGE，
        // 只需要放行这个标记字段。
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(setField("semi", "isByproduct", true)));

        assertTrue((Boolean) envelope.get("success"), String.valueOf(envelope.get("error")));
    }

    @Test
    void byproductFlagOnProcessNodeIsRejected() throws Exception {
        Map<String, Object> envelope = preview(
                definitionWithCategory(SeasoningProcessCategory.COOKING),
                List.of(setField("process:1", "isByproduct", true)));

        assertFalse((Boolean) envelope.get("success"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private Map<String, Object> preview(
            Map<String, Object> definition, List<Map<String, Object>> patches) throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of(
                "definition", definition, "patches", patches));
        return objectMapper.readValue(
                tool.preview(
                        ToolCall.of("preview", tool.getToolName(), arguments),
                        Map.of("factoryId", "F006")),
                new TypeReference<>() {});
    }

    private Map<String, Object> upsertBinding(String nodeId, Map<String, Object> binding) {
        return Map.of("op", "UPSERT_MATERIAL_BINDING", "nodeId", nodeId, "binding", binding);
    }

    private Map<String, Object> binding(String materialTypeId, Double dosage, Double potRatio) {
        return bindingRaw(materialTypeId, dosage, potRatio);
    }

    private Map<String, Object> bindingRaw(String materialTypeId, Object dosage, Object potRatio) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("materialTypeId", materialTypeId);
        if (dosage != null) binding.put("dosagePerKgG", dosage);
        if (potRatio != null) binding.put("subsequentPotRatio", potRatio);
        return binding;
    }

    private Map<String, Object> setField(String nodeId, String path, Object value) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", "SET_NODE_FIELD");
        patch.put("nodeId", nodeId);
        patch.put("path", path);
        patch.put("value", value);
        return patch;
    }

    /**
     * 带一行【已存在】调料绑定的定义。
     *
     * <p>REMOVE_MATERIAL_BINDING 在目标行不存在时会抛 PatchRejectedException
     * （见 applyMaterialBindingPatch 的 removeIf 分支）——用不带绑定的定义去测「删除被拒」，
     * 红的会是「补丁被清洗器拒了」而不是「execute 不许改克数」，那测的是另一件事。
     */
    private Map<String, Object> definitionWithExistingBinding(String processCategory) {
        Map<String, Object> definition =
                new LinkedHashMap<>(definitionWithCategory(processCategory));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object raw : (List<?>) definition.get("nodes")) {
            Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) raw);
            if ("process:1".equals(node.get("id"))) {
                Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) node.get("data"));
                data.put("materialBindings", List.of(binding("RMT-1", 12.5d, null)));
                node.put("data", data);
            }
            nodes.add(node);
        }
        definition.put("nodes", nodes);
        return definition;
    }

    private Map<String, Object> definitionWithCategory(String processCategory) {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-1");
        processData.put("processName", "卤制");
        if (processCategory != null) processData.put("processCategory", processCategory);
        processData.put("inputUnit", "kg");
        processData.put("outputUnit", "kg");
        processData.put("ports", List.of(
                port("input:1", "INPUT", "raw", 0),
                port("output:1", "OUTPUT", "semi", 0)));
        processData.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        processData.put("reportingRequired", true);

        return Map.of(
                "schemaVersion", 1,
                "status", "DRAFT",
                "version", 1,
                "nodes", List.of(
                        materialNode("raw", "RAW_MATERIAL"),
                        Map.of("id", "process:1", "kind", "PROCESS",
                                "position", Map.of("x", 256, "y", 32), "data", processData),
                        materialNode("semi", "SEMI_FINISHED")),
                "edges", List.of(
                        edge("edge:raw:process", "raw", "output", "process:1", "input:1"),
                        edge("edge:process:semi", "process:1", "output:1", "semi", "input")),
                "viewport", Map.of("x", 0, "y", 0, "zoom", 1));
    }

    private Map<String, Object> materialNode(String id, String kind) {
        return Map.of(
                "id", id, "kind", kind,
                "position", Map.of("x", 0, "y", 0),
                "data", Map.of("name", id, "skuId", "SKU-" + id, "baseUnit", "kg", "bound", true));
    }

    private Map<String, Object> port(String id, String direction, String materialNodeId, int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("unit", "kg");
        port.put("ordinal", ordinal);
        return port;
    }

    private Map<String, Object> edge(
            String id, String source, String sourceHandle, String target, String targetHandle) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("sourceHandle", sourceHandle);
        edge.put("target", target);
        edge.put("targetHandle", targetHandle);
        return edge;
    }
}
