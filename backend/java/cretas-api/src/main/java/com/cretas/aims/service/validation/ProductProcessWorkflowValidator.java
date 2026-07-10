package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProductProcessWorkflowValidator {

    private static final Set<String> NODE_KINDS = Set.of(
            "RAW_MATERIAL", "PROCESS", "SEMI_FINISHED", "FINISHED_GOOD");

    public void validateForDraft(ProductProcessWorkflowDTO definition) {
        if (definition == null) {
            invalid("Workflow 草稿不能为空");
        }
        if (!Integer.valueOf(1).equals(definition.getSchemaVersion())) {
            invalid("暂不支持该 Workflow schemaVersion");
        }
        if (definition.getNodes() == null || definition.getEdges() == null) {
            invalid("Workflow 节点和连线不能为空");
        }

        Set<String> nodeIds = new HashSet<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || isBlank(node.getId()) || !NODE_KINDS.contains(node.getKind())) {
                invalid("存在缺少 ID 或类型无效的 Cell");
            }
            if (!nodeIds.add(node.getId())) {
                invalid("Cell ID 不能重复: " + node.getId());
            }
            if (node.getPosition() == null
                    || node.getPosition().getX() == null
                    || node.getPosition().getY() == null) {
                invalid("Cell 必须包含画布坐标: " + node.getId());
            }
        }

        Set<String> edgeIds = new HashSet<>();
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            if (edge == null || isBlank(edge.getId()) || !edgeIds.add(edge.getId())) {
                invalid("连线 ID 不能为空或重复");
            }
            if (!nodeIds.contains(edge.getSource()) || !nodeIds.contains(edge.getTarget())) {
                invalid("连线引用了不存在的 Cell: " + edge.getId());
            }
            if (isBlank(edge.getSourceHandle()) || isBlank(edge.getTargetHandle())) {
                invalid("连线必须绑定明确的投入/产出端口: " + edge.getId());
            }
        }

        if (definition.getViewport() == null
                || definition.getViewport().getZoom() == null
                || definition.getViewport().getZoom() < 0.35D
                || definition.getViewport().getZoom() > 1.8D) {
            invalid("画布缩放值必须在 0.35 到 1.80 之间");
        }
        assertAcyclic(definition.getNodes(), definition.getEdges());
    }

    public void validateForPublish(ProductProcessWorkflowDTO definition) {
        validateForDraft(definition);
        long rawCount = definition.getNodes().stream()
                .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                .count();
        long finishedCount = definition.getNodes().stream()
                .filter(node -> "FINISHED_GOOD".equals(node.getKind()))
                .count();
        if (rawCount == 0 || finishedCount == 0) {
            invalid("发布前至少需要一个原料 Cell 和一个成品 Cell");
        }

        Map<String, Integer> degree = new HashMap<>();
        Set<String> connectedSourceHandles = new HashSet<>();
        Set<String> connectedTargetHandles = new HashSet<>();
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            degree.merge(edge.getSource(), 1, Integer::sum);
            degree.merge(edge.getTarget(), 1, Integer::sum);
            connectedSourceHandles.add(edge.getSource() + "::" + edge.getSourceHandle());
            connectedTargetHandles.add(edge.getTarget() + "::" + edge.getTargetHandle());
        }

        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!degree.containsKey(node.getId())) {
                invalid("存在未连接的 Cell: " + displayName(node));
            }
            Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
            if (!"PROCESS".equals(node.getKind())) {
                if (isBlank(asString(data.get("skuId")))) {
                    invalid("物料 Cell 尚未绑定 SKU: " + displayName(node));
                }
                continue;
            }

            Object portsValue = data.get("ports");
            if (!(portsValue instanceof List<?>) || ((List<?>) portsValue).isEmpty()) {
                invalid("工序必须至少包含投入和产出端口: " + displayName(node));
            }
            List<?> ports = (List<?>) portsValue;
            boolean hasInput = false;
            boolean hasOutput = false;
            for (Object value : ports) {
                if (!(value instanceof Map<?, ?>)) {
                    invalid("工序端口格式错误: " + displayName(node));
                }
                Map<?, ?> port = (Map<?, ?>) value;
                String portId = asString(port.get("id"));
                String direction = asString(port.get("direction"));
                String unit = asString(port.get("unit"));
                if (isBlank(portId) || isBlank(unit)) {
                    invalid("工序端口必须明确单位: " + displayName(node));
                }
                if ("INPUT".equals(direction)) {
                    hasInput = true;
                    if (!connectedTargetHandles.contains(node.getId() + "::" + portId)) {
                        invalid("工序投入端口未连接: " + displayName(node));
                    }
                } else if ("OUTPUT".equals(direction)) {
                    hasOutput = true;
                    if (!connectedSourceHandles.contains(node.getId() + "::" + portId)) {
                        invalid("工序产出端口未连接: " + displayName(node));
                    }
                } else {
                    invalid("工序端口方向无效: " + displayName(node));
                }
            }
            if (!hasInput || !hasOutput) {
                invalid("工序必须同时包含投入和产出端口: " + displayName(node));
            }
        }
    }

    private void assertAcyclic(
            List<ProductProcessWorkflowDTO.Node> nodes,
            List<ProductProcessWorkflowDTO.Edge> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        nodes.forEach(node -> indegree.put(node.getId(), 0));
        for (ProductProcessWorkflowDTO.Edge edge : edges) {
            outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
            indegree.merge(edge.getTarget(), 1, Integer::sum);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, count) -> {
            if (count == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String target : outgoing.getOrDefault(current, List.of())) {
                int next = indegree.merge(target, -1, Integer::sum);
                if (next == 0) {
                    queue.add(target);
                }
            }
        }
        if (visited != nodes.size()) {
            invalid("Workflow 不能形成回路，请检查回流连线");
        }
    }

    private String displayName(ProductProcessWorkflowDTO.Node node) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        String value = asString(data.get("processName"));
        if (isBlank(value)) {
            value = asString(data.get("name"));
        }
        return isBlank(value) ? node.getId() : value;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void invalid(String message) {
        throw new BusinessException(400, message)
                .withCode("PRODUCT_PROCESS_WORKFLOW_INVALID")
                .withHint("请根据提示定位对应 Cell 后再保存或发布")
                .withSeverity("warning");
    }
}
