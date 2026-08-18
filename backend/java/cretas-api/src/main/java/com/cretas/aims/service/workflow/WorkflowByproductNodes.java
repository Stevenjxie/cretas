package com.cretas.aims.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「这个物料 Cell 是不是副产」的**唯一判据**。
 *
 * <p>画布对副产的建模刻意不是第 5 个 {@code kind}, 而是物料节点 {@code data.isByproduct}
 * 标记 —— 与材质分类正交 (见 web-admin {@code workflow/types.ts} 的 MaterialNodeData 注释)。
 * 于是「是不是副产」这句话在仓里一度有三份手写实现 ({@link WorkflowTopologyClassifier}、
 * {@code ProductProcessWorkflowCatalogValidator}、web-admin {@code utils/byproductMaterial.ts}),
 * 而同一个口径写三遍就是「同一个东西有两份, 它一定会漂」。这里把 JVM 侧那条收成一份。
 *
 * <p>⚠️ truthiness 必须容忍字符串 {@code "true"}: nodes_json 是 jsonb, 历史快照里这个标记
 * 既出现过布尔也出现过字符串。只认 {@code Boolean.TRUE} 会让存量副产静默退化成普通半成品 ——
 * 那正是本类要修的那个缺陷的形状。
 */
public final class WorkflowByproductNodes {

    /** 解析运行时快照 nodes_json 用的 mapper —— 只读取, 不参与序列化输出。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowByproductNodes() {
    }

    /**
     * 副产标记的 truthiness。{@code null} / 缺失 一律 false —— 不猜。
     */
    public static boolean isByproductFlag(Object flag) {
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * 从运行时快照 nodes_json 里取出**被标记为副产的物料节点 id 集合**。
     *
     * <p>这是把画布上的副产角色接到报工端的那根线: {@code WorkflowTaskPort.materialNodeId}
     * 就是这里的节点 {@code id}, 两侧靠它 join。⛔ 不要为此在 {@code workflow_task_ports}
     * 上加一列 —— nodes_json 是权威源, 加列等于把同一个事实存两份。
     *
     * <p>解析失败返回空集合 (即「都不是副产」), 与既有 {@code resolveAllowMultipleUpstreamSources}
     * 的容错口径一致: 一个 JSON 解析问题不该让整张报工单打不开。
     */
    public static Set<String> byproductMaterialNodeIds(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode nodes = MAPPER.readTree(nodesJson);
            if (!nodes.isArray()) {
                return Collections.emptySet();
            }
            Set<String> marked = new LinkedHashSet<>();
            for (JsonNode node : nodes) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                JsonNode flag = node.path("data").path("isByproduct");
                if (flag.isMissingNode() || flag.isNull()) {
                    continue;
                }
                if (isByproductFlag(flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
                    marked.add(id);
                }
            }
            return marked;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }
}
