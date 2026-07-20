package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
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
    private static final Set<String> MATERIAL_NODE_KINDS = Set.of(
            "RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD");
    private static final Set<String> SELECTION_GROUP_MODES = Set.of(
            "ALL_REQUIRED", "EXACTLY_ONE", "AT_LEAST_ONE", "OPTIONAL");

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
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = new HashMap<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || isBlank(node.getId()) || !NODE_KINDS.contains(node.getKind())) {
                invalid("存在缺少 ID 或类型无效的 Cell");
            }
            if (!nodeIds.add(node.getId())) {
                invalid("Cell ID 不能重复: " + node.getId());
            }
            nodesById.put(node.getId(), node);
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

        validateGraphSemantics(definition, nodesById);
        if (definition.getViewport() == null
                || definition.getViewport().getZoom() == null
                || definition.getViewport().getZoom() < 0.35D
                || definition.getViewport().getZoom() > 1.8D) {
            invalid("画布缩放值必须在 0.35 到 1.80 之间");
        }
        assertAcyclic(definition.getNodes(), definition.getEdges());
    }

    public void validateForPublish(ProductProcessWorkflowDTO definition) {
        validateStructureComplete(definition);
    }

    /**
     * Validate that a DRAFT already describes a complete executable process chain.
     *
     * <p>This deliberately excludes BOM activation/catalog checks. It is the gate that
     * opens BOM configuration in the workflow-first product-definition state machine.
     */
    public void validateStructureComplete(ProductProcessWorkflowDTO definition) {
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
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            degree.merge(edge.getSource(), 1, Integer::sum);
            degree.merge(edge.getTarget(), 1, Integer::sum);
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
                } else if ("OUTPUT".equals(direction)) {
                    hasOutput = true;
                } else {
                    invalid("工序端口方向无效: " + displayName(node));
                }
            }
            if (!hasInput || !hasOutput) {
                invalid("工序必须同时包含投入和产出端口: " + displayName(node));
            }
        }
    }

    private void validateGraphSemantics(
            ProductProcessWorkflowDTO definition,
            Map<String, ProductProcessWorkflowDTO.Node> nodesById) {
        Map<String, Map<String, PortBinding>> portsByProcess = new HashMap<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!"PROCESS".equals(node.getKind())) {
                continue;
            }
            Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
            Object rawPorts = data.get("ports");
            if (!(rawPorts instanceof List<?>)) {
                invalid("工序必须包含端口列表: " + displayName(node));
            }
            List<?> ports = (List<?>) rawPorts;

            Map<String, PortBinding> portsById = new HashMap<>();
            Map<String, Set<BigInteger>> ordinalsByDirection = Map.of(
                    "INPUT", new HashSet<>(),
                    "OUTPUT", new HashSet<>());
            for (Object rawPort : ports) {
                if (!(rawPort instanceof Map<?, ?>)) {
                    invalid("工序端口格式错误: " + displayName(node));
                }
                Map<?, ?> port = (Map<?, ?>) rawPort;
                String portId = asString(port.get("id"));
                String direction = asString(port.get("direction"));
                String materialNodeId = asString(port.get("materialNodeId"));
                if (isBlank(portId) || !("INPUT".equals(direction) || "OUTPUT".equals(direction))) {
                    invalid("工序端口 ID 或方向无效: " + displayName(node));
                }
                if (portsById.containsKey(portId)) {
                    invalid("工序端口 ID 不能重复: " + portId);
                }
                BigInteger ordinal = nonNegativeInteger(port.get("ordinal"));
                if (ordinal == null || !ordinalsByDirection.get(direction).add(ordinal)) {
                    invalid("工序端口 ordinal 必须是方向内唯一的非负整数: " + displayName(node));
                }
                ProductProcessWorkflowDTO.Node material = nodesById.get(materialNodeId);
                if (isBlank(materialNodeId)
                        || material == null
                        || !MATERIAL_NODE_KINDS.contains(material.getKind())) {
                    invalid("工序端口必须引用已存在的物料 Cell: " + portId);
                }
                Object materialKind = port.get("materialKind");
                if (materialKind != null && !material.getKind().equals(materialKind)) {
                    invalid("工序端口物料类型与引用 Cell 不一致: " + portId);
                }
                portsById.put(portId, new PortBinding(direction, materialNodeId));
            }
            validatePortGroups(node, data.get("portGroups"), portsById);
            portsByProcess.put(node.getId(), portsById);
        }

        Map<PortKey, Integer> matchedEdgeCount = new HashMap<>();
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            ProductProcessWorkflowDTO.Node source = nodesById.get(edge.getSource());
            ProductProcessWorkflowDTO.Node target = nodesById.get(edge.getTarget());
            boolean sourceIsProcess = "PROCESS".equals(source.getKind());
            boolean targetIsProcess = "PROCESS".equals(target.getKind());
            if (source.getId().equals(target.getId()) || sourceIsProcess == targetIsProcess) {
                invalid("连线必须连接一个物料 Cell 和一个工序 Cell: " + edge.getId());
            }

            String processId;
            String portId;
            PortBinding binding;
            if (sourceIsProcess) {
                processId = source.getId();
                portId = edge.getSourceHandle();
                binding = portsByProcess.getOrDefault(processId, Map.of()).get(portId);
                if (binding == null
                        || !"OUTPUT".equals(binding.direction())
                        || !binding.materialNodeId().equals(target.getId())
                        || !"input".equals(edge.getTargetHandle())) {
                    invalid("工序产出连线与端口声明不一致: " + edge.getId());
                }
            } else {
                processId = target.getId();
                portId = edge.getTargetHandle();
                binding = portsByProcess.getOrDefault(processId, Map.of()).get(portId);
                if (binding == null
                        || !"INPUT".equals(binding.direction())
                        || !binding.materialNodeId().equals(source.getId())
                        || !"output".equals(edge.getSourceHandle())) {
                    invalid("工序投入连线与端口声明不一致: " + edge.getId());
                }
            }

            PortKey bindingKey = new PortKey(processId, portId);
            if (matchedEdgeCount.merge(bindingKey, 1, Integer::sum) != 1) {
                invalid("一个工序端口只能对应一条连线: " + portId);
            }
        }

        portsByProcess.forEach((processId, ports) -> ports.forEach((portId, binding) -> {
            if (matchedEdgeCount.getOrDefault(new PortKey(processId, portId), 0) != 1) {
                invalid("工序端口必须且只能连接一次: " + portId);
            }
        }));
    }

    private void validatePortGroups(
            ProductProcessWorkflowDTO.Node node,
            Object rawValue,
            Map<String, PortBinding> portsById) {
        if (rawValue == null) {
            return;
        }
        if (!(rawValue instanceof List<?>)) {
            invalid("工序端口选择组必须是数组: " + displayName(node));
        }
        List<?> rawGroups = (List<?>) rawValue;

        Set<String> groupIds = new HashSet<>();
        Set<String> assignedPortIds = new HashSet<>();
        for (Object rawGroup : rawGroups) {
            if (!(rawGroup instanceof Map<?, ?>)) {
                invalid("工序端口选择组格式错误: " + displayName(node));
            }
            Map<?, ?> group = (Map<?, ?>) rawGroup;
            String groupId = asString(group.get("id"));
            String direction = asString(group.get("direction"));
            String label = asString(group.get("label"));
            String mode = asString(group.get("mode"));
            if (isBlank(groupId) || !groupIds.add(groupId)) {
                invalid("工序端口选择组 ID 不能为空或重复: " + displayName(node));
            }
            if (!"INPUT".equals(direction) && !"OUTPUT".equals(direction)) {
                invalid("工序端口选择组方向无效: " + groupId);
            }
            if (isBlank(label) || !SELECTION_GROUP_MODES.contains(mode)) {
                invalid("工序端口选择组标签或模式无效: " + groupId);
            }
            BigInteger minSelections = nonNegativeInteger(group.get("minSelections"));
            BigInteger maxSelections = nonNegativeInteger(group.get("maxSelections"));
            if (minSelections == null || maxSelections == null
                    || minSelections.compareTo(maxSelections) > 0) {
                invalid("工序端口选择组 min/max 无效: " + groupId);
            }
            Object rawPortIds = group.get("portIds");
            if (!(rawPortIds instanceof List<?>) || ((List<?>) rawPortIds).isEmpty()) {
                invalid("工序端口选择组必须引用至少一个端口: " + groupId);
            }
            List<?> portIds = (List<?>) rawPortIds;
            Set<String> groupPortIds = new HashSet<>();
            for (Object rawPortId : portIds) {
                String portId = asString(rawPortId);
                PortBinding port = portsById.get(portId);
                if (isBlank(portId) || port == null) {
                    invalid("工序端口选择组引用了不存在的端口: " + groupId);
                }
                if (!groupPortIds.add(portId)) {
                    invalid("工序端口选择组不能重复引用端口: " + portId);
                }
                if (!direction.equals(port.direction())) {
                    invalid("工序端口选择组方向与端口不一致: " + portId);
                }
                if (!assignedPortIds.add(portId)) {
                    invalid("工序端口不能归属多个选择组: " + portId);
                }
            }
            BigInteger portCount = BigInteger.valueOf(groupPortIds.size());
            if (maxSelections.compareTo(portCount) > 0
                    || !selectionBoundsMatchMode(mode, minSelections, maxSelections, portCount)) {
                invalid("工序端口选择组 min/max 与模式不一致: " + groupId);
            }
        }
    }

    private boolean selectionBoundsMatchMode(
            String mode,
            BigInteger minSelections,
            BigInteger maxSelections,
            BigInteger portCount) {
        return switch (mode) {
            case "ALL_REQUIRED" -> minSelections.equals(portCount) && maxSelections.equals(portCount);
            case "EXACTLY_ONE" -> BigInteger.ONE.equals(minSelections)
                    && BigInteger.ONE.equals(maxSelections);
            case "AT_LEAST_ONE" -> BigInteger.ONE.equals(minSelections)
                    && maxSelections.equals(portCount);
            case "OPTIONAL" -> BigInteger.ZERO.equals(minSelections)
                    && maxSelections.equals(portCount);
            default -> false;
        };
    }

    private BigInteger nonNegativeInteger(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            BigInteger integer;
            if (number instanceof BigInteger bigInteger) {
                integer = bigInteger;
            } else if (number instanceof BigDecimal bigDecimal) {
                integer = bigDecimal.toBigIntegerExact();
            } else if (number instanceof Byte
                    || number instanceof Short
                    || number instanceof Integer
                    || number instanceof Long) {
                integer = BigInteger.valueOf(number.longValue());
            } else {
                double doubleValue = number.doubleValue();
                if (!Double.isFinite(doubleValue)) {
                    return null;
                }
                integer = BigDecimal.valueOf(doubleValue).toBigIntegerExact();
            }
            return integer.signum() < 0 ? null : integer;
        } catch (ArithmeticException ignored) {
            return null;
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
        return value instanceof String string ? string : null;
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

    private record PortBinding(
            String direction,
            String materialNodeId) {
    }

    private record PortKey(
            String processId,
            String portId) {
    }
}
