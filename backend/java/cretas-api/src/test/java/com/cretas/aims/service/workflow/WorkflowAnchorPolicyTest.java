package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「归属对象按研判自动更改」的规则闸。
 *
 * <p>Steve 2026-08-11 的判据: 研判早就能认出「原料分流」并显示在顶部, 但没有任何代码拿这个结论去改
 * 归属对象 —— 「你只是标注原料分流不是很根治」。本测试钉住四种研判各自该给出什么归属。
 */
class WorkflowAnchorPolicyTest {

    private static ProductProcessWorkflowDTO.Node node(
            String id, String kind, String skuId, String substituteOfNodeId, boolean byproduct) {
        ProductProcessWorkflowDTO.Node n = new ProductProcessWorkflowDTO.Node();
        n.setId(id);
        n.setKind(kind);
        Map<String, Object> data = new HashMap<>();
        if (skuId != null) data.put("skuId", skuId);
        if (substituteOfNodeId != null) data.put("substituteOfNodeId", substituteOfNodeId);
        if (byproduct) data.put("isByproduct", true);
        n.setData(data);
        return n;
    }

    private static ProductProcessWorkflowDTO.Edge edge(String source, String target) {
        ProductProcessWorkflowDTO.Edge e = new ProductProcessWorkflowDTO.Edge();
        e.setSource(source);
        e.setTarget(target);
        return e;
    }

    private static ProductProcessWorkflowDTO graph(
            List<ProductProcessWorkflowDTO.Node> nodes, List<ProductProcessWorkflowDTO.Edge> edges) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setNodes(new ArrayList<>(nodes));
        dto.setEdges(new ArrayList<>(edges));
        return dto;
    }

    private static Optional<String> resolve(ProductProcessWorkflowDTO dto) {
        return WorkflowAnchorPolicy.desiredOwner(dto, WorkflowTopologyClassifier.classify(dto));
    }

    @Test
    @DisplayName("单原料单成品 → 归属对象是那个成品")
    void singleOutputAnchorsToTheProduct() {
        ProductProcessWorkflowDTO dto = graph(
                List.of(
                        node("raw", "RAW_MATERIAL", "RMT_A", null, false),
                        node("p1", "PROCESS", null, null, false),
                        node("fg", "FINISHED_GOOD", "PT_C", null, false)),
                List.of(edge("raw", "p1"), edge("p1", "fg")));

        assertThat(WorkflowTopologyClassifier.classify(dto).type())
                .isEqualTo(WorkflowTopology.Type.SINGLE_OUTPUT_PRODUCT);
        assertThat(resolve(dto)).contains("PT_C");
    }

    @Test
    @DisplayName("🔴 单原料多成品(原料分流) → 归属对象自动变成那个共享原料, 不再钉在某一个成品上")
    void rawMaterialSplitAnchorsToTheSharedRawMaterial() {
        ProductProcessWorkflowDTO dto = graph(
                List.of(
                        node("raw", "RAW_MATERIAL", "RMT_A", null, false),
                        node("p1", "PROCESS", null, null, false),
                        node("fgC", "FINISHED_GOOD", "PT_C", null, false),
                        node("fgD", "FINISHED_GOOD", "PT_D", null, false)),
                List.of(edge("raw", "p1"), edge("p1", "fgC"), edge("p1", "fgD")));

        assertThat(WorkflowTopologyClassifier.classify(dto).type())
                .isEqualTo(WorkflowTopology.Type.RAW_MATERIAL_SPLIT);
        assertThat(resolve(dto))
                .as("这就是 F006 拓扑成品C/D 那张图 —— 归属必须是原料 RMT_A, 不是成品 PT_C")
                .contains("RMT_A");
    }

    @Test
    @DisplayName("副产不算终端产出 → 一个成品 + 一个副产仍是单成品, 归属是成品")
    void byproductDoesNotMakeItASplit() {
        ProductProcessWorkflowDTO dto = graph(
                List.of(
                        node("raw", "RAW_MATERIAL", "RMT_A", null, false),
                        node("p1", "PROCESS", null, null, false),
                        node("fg", "FINISHED_GOOD", "PT_C", null, false),
                        node("by", "FINISHED_GOOD", "RMT_OIL", null, true)),
                List.of(edge("raw", "p1"), edge("p1", "fg"), edge("p1", "by")));

        assertThat(resolve(dto)).contains("PT_C");
    }

    @Test
    @DisplayName("替代料组算一个逻辑投入 → 归属取组里的主原料(不是别人的替代品那个)")
    void substituteGroupAnchorsToItsPrimary() {
        ProductProcessWorkflowDTO dto = graph(
                List.of(
                        node("rawMain", "RAW_MATERIAL", "RMT_MAIN", null, false),
                        node("rawAlt", "RAW_MATERIAL", "RMT_ALT", "rawMain", false),
                        node("p1", "PROCESS", null, null, false),
                        node("fgC", "FINISHED_GOOD", "PT_C", null, false),
                        node("fgD", "FINISHED_GOOD", "PT_D", null, false)),
                List.of(edge("rawMain", "p1"), edge("rawAlt", "p1"),
                        edge("p1", "fgC"), edge("p1", "fgD")));

        assertThat(WorkflowTopologyClassifier.classify(dto).type())
                .as("互为替代的两个根原料算一个逻辑投入 → 仍是原料分流, 不是联产")
                .isEqualTo(WorkflowTopology.Type.RAW_MATERIAL_SPLIT);
        assertThat(resolve(dto))
                .as("RMT_ALT 是 RMT_MAIN 的替代品, 组的原点是 RMT_MAIN")
                .contains("RMT_MAIN");
    }

    @Test
    @DisplayName("⛔ 多原料多成品(联产) → 不动归属对象, 任选一个当锚都是编出来的")
    void jointProductionKeepsCurrentAnchor() {
        ProductProcessWorkflowDTO dto = graph(
                List.of(
                        node("rawA", "RAW_MATERIAL", "RMT_A", null, false),
                        node("rawB", "RAW_MATERIAL", "RMT_B", null, false),
                        node("p1", "PROCESS", null, null, false),
                        node("fgC", "FINISHED_GOOD", "PT_C", null, false),
                        node("fgD", "FINISHED_GOOD", "PT_D", null, false)),
                List.of(edge("rawA", "p1"), edge("rawB", "p1"),
                        edge("p1", "fgC"), edge("p1", "fgD")));

        assertThat(WorkflowTopologyClassifier.classify(dto).type())
                .isEqualTo(WorkflowTopology.Type.JOINT_PRODUCTION);
        assertThat(resolve(dto))
                .as("联产没有唯一的原料也没有唯一的成品; 随便挑一个比留在原地更误导")
                .isEmpty();
    }

    @Test
    @DisplayName("结构不完整 → 不动归属对象")
    void invalidTopologyKeepsCurrentAnchor() {
        ProductProcessWorkflowDTO dto = graph(
                List.of(node("raw", "RAW_MATERIAL", "RMT_A", null, false)),
                List.of());

        assertThat(resolve(dto)).isEmpty();
    }

    @Test
    @DisplayName("空图 / null 一律不动")
    void nullSafe() {
        assertThat(WorkflowAnchorPolicy.desiredOwner(null, null)).isEmpty();
        assertThat(resolve(graph(List.of(), List.of()))).isEmpty();
    }
}
