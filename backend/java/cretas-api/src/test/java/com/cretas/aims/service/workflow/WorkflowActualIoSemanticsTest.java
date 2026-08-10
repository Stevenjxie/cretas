package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替代料载体 {@code substituteOfNodeId} 的**往返存活**证明。
 *
 * <p>「写进去了」不等于「读得回来」。saveDraft 的实际链路是
 * {@code normalizeDraft → writeJson(nodes) → readJson(nodes)}，
 * 这个类把这条链原样跑一遍，证明字段没有在任何一段被吃掉。
 *
 * <p>⛔ 载体刻意**不用**工序的 {@code portGroups}：normalizeDraft 每次保存都
 * {@code data.remove("portGroups")}，且 RuntimeCompiler 在 ACTUAL_IO 下完全绕过它。
 * 物料节点不被 normalizeDraft 清洗 —— 这里的第二条断言就是钉这个前提的。
 */
class WorkflowActualIoSemanticsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsSubstituteOfNodeIdOnMaterialNodesThroughNormalization() {
        ProductProcessWorkflowDTO definition = twoRawMaterialsWithSubstitute();

        WorkflowActualIoSemantics.normalizeDraft(definition);

        Object kept = definition.getNodes().stream()
                .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                .filter(node -> node.getData().containsKey("substituteOfNodeId"))
                .findFirst().orElseThrow()
                .getData().get("substituteOfNodeId");
        assertEquals("raw:1", kept);
    }

    @Test
    void keepsSubstituteOfNodeIdThroughJsonPersistenceRoundTrip() throws Exception {
        ProductProcessWorkflowDTO definition = twoRawMaterialsWithSubstitute();

        // saveDraft 的真实链路: normalizeDraft → writeJson(nodes) → (DB) → readJson(nodes)
        WorkflowActualIoSemantics.normalizeDraft(definition);
        String nodesJson = objectMapper.writeValueAsString(definition.getNodes());
        List<ProductProcessWorkflowDTO.Node> reloaded = objectMapper.readValue(
                nodesJson, new TypeReference<List<ProductProcessWorkflowDTO.Node>>() {});

        ProductProcessWorkflowDTO.Node substitute = reloaded.stream()
                .filter(node -> "raw:2".equals(node.getId()))
                .findFirst().orElseThrow();
        assertEquals("raw:1", substitute.getData().get("substituteOfNodeId"));

        ProductProcessWorkflowDTO.Node main = reloaded.stream()
                .filter(node -> "raw:1".equals(node.getId()))
                .findFirst().orElseThrow();
        assertFalse(main.getData().containsKey("substituteOfNodeId"),
                "主料不该被写上替代关系");
    }

    /**
     * 反面对照: 同一次 normalizeDraft 会把工序上的 portGroups 删掉。
     * 这条断言证明「换载体」不是口味问题 —— 旧载体确实活不过保存。
     */
    @Test
    void dropsPortGroupsFromProcessNodesSoTheyCannotCarrySubstituteGroups() {
        ProductProcessWorkflowDTO definition = twoRawMaterialsWithSubstitute();
        ProductProcessWorkflowDTO.Node process = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst().orElseThrow();
        assertTrue(process.getData().containsKey("portGroups"), "夹具本身要先带上 portGroups");

        WorkflowActualIoSemantics.normalizeDraft(definition);

        ProductProcessWorkflowDTO.Node normalized = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst().orElseThrow();
        assertFalse(normalized.getData().containsKey("portGroups"),
                "portGroups 被 normalizeDraft 删掉 —— 替代关系不能放在那里");
        assertEquals(WorkflowActualIoSemantics.ACTUAL_IO,
                normalized.getData().get(WorkflowActualIoSemantics.MODE_FIELD));
    }

    private ProductProcessWorkflowDTO twoRawMaterialsWithSubstitute() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setSchemaVersion(1);
        definition.setStatus("DRAFT");

        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>();
        nodes.add(materialNode("raw:1", "RAW_MATERIAL", materialData("主料 猪前腿", "RM-1", null)));
        nodes.add(materialNode("raw:2", "RAW_MATERIAL", materialData("替代 猪后腿", "RM-2", "raw:1")));
        nodes.add(processNode("process:1"));
        nodes.add(materialNode("semi:1", "SEMI_FINISHED", materialData("半成品", "SFI-1", null)));
        definition.setNodes(nodes);
        definition.setEdges(new ArrayList<>());
        return definition;
    }

    private Map<String, Object> materialData(String name, String skuId, String substituteOfNodeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("skuId", skuId);
        data.put("baseUnit", "kg");
        if (substituteOfNodeId != null) {
            data.put("substituteOfNodeId", substituteOfNodeId);
        }
        return data;
    }

    private ProductProcessWorkflowDTO.Node materialNode(
            String id, String kind, Map<String, Object> data) {
        ProductProcessWorkflowDTO.Node node = new ProductProcessWorkflowDTO.Node();
        node.setId(id);
        node.setKind(kind);
        node.setPosition(new ProductProcessWorkflowDTO.Position(16.0, 32.0));
        node.setData(data);
        return node;
    }

    private ProductProcessWorkflowDTO.Node processNode(String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-1");
        data.put("processName", "切配");
        data.put("inputUnit", "kg");
        data.put("outputUnit", "kg");
        data.put("ports", new ArrayList<>());
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "group:1");
        group.put("direction", "INPUT");
        group.put("label", "投其一");
        group.put("mode", "EXACTLY_ONE");
        group.put("minSelections", 1);
        group.put("maxSelections", 1);
        group.put("portIds", List.of("input:1", "input:2"));
        data.put("portGroups", new ArrayList<>(List.of(group)));

        ProductProcessWorkflowDTO.Node node = new ProductProcessWorkflowDTO.Node();
        node.setId(id);
        node.setKind("PROCESS");
        node.setPosition(new ProductProcessWorkflowDTO.Position(256.0, 32.0));
        node.setData(data);
        return node;
    }
}
