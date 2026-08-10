package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Classifies a Workflow from graph shape only; product_type_id is only a legacy persistence anchor. */
public final class WorkflowTopologyClassifier {

    private WorkflowTopologyClassifier() {
    }

    public static WorkflowTopology classify(ProductProcessWorkflowDTO definition) {
        if (definition == null || definition.getNodes() == null || definition.getEdges() == null) {
            return new WorkflowTopology(WorkflowTopology.Type.INVALID, List.of(), List.of(), 0);
        }
        Set<String> withIncoming = definition.getEdges().stream()
                .map(ProductProcessWorkflowDTO.Edge::getTarget)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> withOutgoing = definition.getEdges().stream()
                .map(ProductProcessWorkflowDTO.Edge::getSource)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> terminals = new TreeSet<>();
        Set<String> roots = new TreeSet<>();
        Map<String, String> rootSkuByNodeId = new HashMap<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getId() == null || node.getData() == null) continue;
            String skuId = stringValue(node.getData(), "skuId");
            if (skuId == null) continue;
            if ("FINISHED_GOOD".equals(node.getKind())
                    && !withOutgoing.contains(node.getId())
                    && !isByproduct(node)) {
                terminals.add(skuId);
            }
            if ("RAW_MATERIAL".equals(node.getKind()) && !withIncoming.contains(node.getId())) {
                roots.add(skuId);
                rootSkuByNodeId.put(node.getId(), skuId);
            }
        }
        int logicalRootCount = logicalRootCount(definition, rootSkuByNodeId.keySet());
        WorkflowTopology.Type type;
        if (terminals.size() == 1) {
            type = WorkflowTopology.Type.SINGLE_OUTPUT_PRODUCT;
        } else if (terminals.size() > 1 && logicalRootCount == 1) {
            type = WorkflowTopology.Type.RAW_MATERIAL_SPLIT;
        } else if (terminals.size() > 1 && logicalRootCount > 1) {
            type = WorkflowTopology.Type.JOINT_PRODUCTION;
        } else {
            type = WorkflowTopology.Type.INVALID;
        }
        return new WorkflowTopology(type, List.copyOf(terminals), List.copyOf(roots), logicalRootCount);
    }

    /**
     * 一组「互为替代」的根原料算一个逻辑投入 —— 它们是二选一, 不是同时都要。
     *
     * <p>2026-08-10: 载体从工序的 EXACTLY_ONE 端口组换成原料节点自己的
     * substituteOfNodeId。原因见 spec §5.2b: portGroups 会被
     * {@code WorkflowActualIoSemantics#normalizeDraft} 每次保存都删掉, 且
     * RuntimeCompiler 在 ACTUAL_IO 下完全绕过它, prod 35 条 revision 里一条
     * portGroups 都没有 —— 那条路从来没生效过。
     *
     * <p>合法性(自引用/悬空/成链)由 {@code ProductProcessWorkflowValidator} 保证,
     * 这里只做合并, 不重复校验。
     */
    private static int logicalRootCount(
            ProductProcessWorkflowDTO definition, Set<String> rootNodeIds) {
        if (rootNodeIds.isEmpty()) return 0;
        Map<String, String> parent = new HashMap<>();
        rootNodeIds.forEach(id -> parent.put(id, id));
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || !rootNodeIds.contains(node.getId()) || node.getData() == null) continue;
            String target = stringValue(node.getData(), "substituteOfNodeId");
            if (target == null || !rootNodeIds.contains(target)) continue;
            union(parent, target, node.getId());
        }
        return (int) rootNodeIds.stream().map(id -> find(parent, id)).distinct().count();
    }

    private static void union(Map<String, String> parent, String left, String right) {
        String leftRoot = find(parent, left);
        String rightRoot = find(parent, right);
        if (!leftRoot.equals(rightRoot)) parent.put(rightRoot, leftRoot);
    }

    private static String find(Map<String, String> parent, String value) {
        String current = parent.getOrDefault(value, value);
        while (!current.equals(parent.getOrDefault(current, current))) {
            current = parent.get(current);
        }
        parent.put(value, current);
        return current;
    }

    /**
     * 副产是「附带出来的物料」而不是「要生产的成品」——不能进终端产出集合。
     *
     * <p>2026-08-10: 生产计划改为精确匹配(勾选集合必须等于终端产出集合)之后，副产若留在
     * 集合里，建计划就会要求用户把副产也勾上。画布对副产的建模是「普通产出节点 +
     * isByproduct 标记」(刻意没有 kind:'BYPRODUCT'，与材质正交)，所以只认这个标记 ——
     * 与 ProductProcessWorkflowCatalogValidator#isByproductNode 同口径。
     *
     * <p>⚠️ 收窄会传导到 isSingleOutput()/isMultiOutput()：「主成品 + 副产」图变成单产出，
     * 「只有副产」图变成 INVALID。两者都已被测试钉住。
     */
    private static boolean isByproduct(ProductProcessWorkflowDTO.Node node) {
        Object flag = node.getData().get("isByproduct");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    private static String stringValue(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
