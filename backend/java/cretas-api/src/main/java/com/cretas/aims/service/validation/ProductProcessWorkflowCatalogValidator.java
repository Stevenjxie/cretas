package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ProductProcessWorkflowCatalogValidator {

    private static final Set<String> FINISHED_OUTPUT_CATEGORIES = Set.of(
            ProductCategory.FINISHED_PRODUCT,
            ProductCategory.CONTRACT_MANUFACTURING,
            ProductCategory.CUSTOMER_MATERIAL,
            ProductCategory.DISH,
            ProductCategory.COMBO);

    private final WorkProcessRepository workProcessRepository;
    private final ProductTypeRepository productTypeRepository;

    public void validateForPublish(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition) {
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = definition.getNodes().stream()
                .collect(Collectors.toMap(
                        ProductProcessWorkflowDTO.Node::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ProductProcessWorkflowDTO.Node> processNodes = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .toList();
        if (processNodes.isEmpty()) {
            ProductProcessWorkflowDTO.Node relatedMaterial = definition.getNodes().stream()
                    .filter(node -> "FINISHED_GOOD".equals(node.getKind()))
                    .findFirst()
                    .orElseGet(() -> definition.getNodes().stream()
                            .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                            .findFirst()
                            .orElse(definition.getNodes().getFirst()));
            mismatch(relatedMaterial, "Workflow 没有工序 Cell，无法验证产出目录绑定",
                    "请在原料与成品之间添加真实工序 Cell");
        }

        LinkedHashSet<String> workProcessIds = new LinkedHashSet<>();
        for (ProductProcessWorkflowDTO.Node processNode : processNodes) {
            String workProcessId = asString(data(processNode).get("workProcessId"));
            if (isBlank(workProcessId)) {
                mismatch(processNode, "未绑定真实工序目录", "请重新选择该 Cell 的工序");
            }
            workProcessIds.add(workProcessId);
        }

        List<WorkProcess> workProcessRows = workProcessRepository.findByFactoryIdAndIdIn(
                factoryId, new ArrayList<>(workProcessIds));
        Map<String, WorkProcess> workProcessesById = indexWorkProcesses(
                factoryId, workProcessIds, processNodes, workProcessRows);

        List<OutputBinding> outputBindings = new ArrayList<>();
        for (ProductProcessWorkflowDTO.Node processNode : processNodes) {
            String workProcessId = asString(data(processNode).get("workProcessId"));
            WorkProcess workProcess = workProcessesById.get(workProcessId);
            if (workProcess == null) {
                mismatch(processNode, "引用的工序不属于当前工厂或已不存在: " + workProcessId,
                        "请重新选择当前工厂中的有效工序");
            }

            List<Port> outputPorts = outputPorts(processNode);
            Map<String, Port> portsById = outputPorts.stream()
                    .collect(Collectors.toMap(Port::id, Function.identity(), (left, right) -> left,
                            LinkedHashMap::new));
            Map<String, List<ProductProcessWorkflowDTO.Edge>> edgesByHandle = new LinkedHashMap<>();
            for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
                if (!processNode.getId().equals(edge.getSource())) {
                    continue;
                }
                if (!portsById.containsKey(edge.getSourceHandle())) {
                    mismatch(processNode,
                            "存在未声明产出端口的连线: " + edge.getSourceHandle(),
                            "请删除幽灵连线并从已声明产出端口重新连接");
                }
                edgesByHandle.computeIfAbsent(edge.getSourceHandle(), ignored -> new ArrayList<>()).add(edge);
            }

            for (Port outputPort : outputPorts) {
                List<ProductProcessWorkflowDTO.Edge> portEdges = edgesByHandle.getOrDefault(
                        outputPort.id(), List.of());
                if (portEdges.size() != 1) {
                    mismatch(processNode,
                            "产出端口 " + outputPort.id() + " 必须且只能连接一个物料 Cell，当前连线数: "
                                    + portEdges.size(),
                            "请保留该端口到目标物料 Cell 的唯一连线");
                }
                ProductProcessWorkflowDTO.Edge edge = portEdges.getFirst();
                if (!outputPort.materialNodeId().equals(edge.getTarget())) {
                    mismatch(processNode,
                            "产出端口 " + outputPort.id() + " 与目标物料 Cell 绑定不一致",
                            "请删除并重新连接该产出端口");
                }
                ProductProcessWorkflowDTO.Node materialNode = resolveOutputMaterial(
                        processNode, outputPort, nodesById);
                if (!materialNode.getKind().equals(outputPort.materialKind())) {
                    mismatch(materialNode,
                            "产出端口类型 " + outputPort.materialKind()
                                    + " 与物料 Cell 类型 " + materialNode.getKind() + " 不一致",
                            "请在工序 Cell 中重新绑定该产出物料");
                }
                outputBindings.add(new OutputBinding(materialNode));
            }

            OutputBinding primaryOutput = outputPorts.stream()
                    .min(Comparator.comparingInt(Port::ordinal))
                    .map(port -> new OutputBinding(resolveOutputMaterial(processNode, port, nodesById)))
                    .orElseThrow(() -> catalogMismatch(processNode,
                            "没有可校验的产出端口", "请为该工序至少配置一个产出 Cell"));
            String expectedKind = workProcess.getDefaultOutputMaterialKind() == null
                    ? null
                    : workProcess.getDefaultOutputMaterialKind().name();
            if (!primaryOutput.materialNode().getKind().equals(expectedKind)) {
                mismatch(primaryOutput.materialNode(),
                        "主产出类型 " + primaryOutput.materialNode().getKind()
                                + " 与工序目录默认产出类型 " + expectedKind + " 不一致",
                        "请重新选择工序或调整该主产出 Cell");
            }
        }

        validateOutputSkus(factoryId, productTypeId, outputBindings);
    }

    private void validateOutputSkus(
            String factoryId,
            String productTypeId,
            List<OutputBinding> outputBindings) {
        LinkedHashSet<String> skuIds = new LinkedHashSet<>();
        for (OutputBinding binding : outputBindings) {
            String skuId = asString(data(binding.materialNode()).get("skuId"));
            if (isBlank(skuId)) {
                mismatch(binding.materialNode(), "未绑定产出 SKU", "请为该产出 Cell 选择 SKU");
            }
            skuIds.add(skuId);
        }

        List<ProductType> productRows = productTypeRepository.findByIdIn(new ArrayList<>(skuIds));
        Map<String, ProductType> productsById = indexProducts(factoryId, skuIds, outputBindings, productRows);
        for (OutputBinding binding : outputBindings) {
            ProductProcessWorkflowDTO.Node materialNode = binding.materialNode();
            String skuId = asString(data(materialNode).get("skuId"));
            ProductType product = productsById.get(skuId);
            if (product == null) {
                mismatch(materialNode, "产出 SKU 不存在: " + skuId, "请重新选择有效的产出 SKU");
            }
            if (!factoryId.equals(product.getFactoryId())) {
                mismatch(materialNode, "产出 SKU 不属于当前工厂: " + skuId,
                        "请改选当前工厂的产出 SKU");
            }

            String category = product.getProductCategory();
            if ("SEMI_FINISHED".equals(materialNode.getKind())) {
                if (!ProductCategory.SEMI_FINISHED.equals(category)) {
                    mismatch(materialNode,
                            "半成品 Cell 只能绑定 SEMI_FINISHED 分类 SKU，当前分类: "
                                    + displayCategory(category),
                            "请改选半成品 SKU");
                }
            } else if ("FINISHED_GOOD".equals(materialNode.getKind())
                    && (category == null || !FINISHED_OUTPUT_CATEGORIES.contains(category))) {
                mismatch(materialNode,
                        "成品 Cell 绑定了非法产出分类 " + displayCategory(category)
                                + "（Workflow 产品 " + productTypeId + " 可绑定其他合法成品版本）",
                        "请改选成品、代工品、客供成品、菜品或套餐 SKU");
            }
        }
    }

    private List<Port> outputPorts(ProductProcessWorkflowDTO.Node processNode) {
        Object portsValue = data(processNode).get("ports");
        if (!(portsValue instanceof List<?> ports)) {
            throw catalogMismatch(processNode, "工序端口数据缺失", "请重新配置该工序的产出端口");
        }
        List<Port> outputPorts = new ArrayList<>();
        Set<String> portIds = new LinkedHashSet<>();
        Set<String> materialNodeIds = new LinkedHashSet<>();
        Set<Integer> ordinals = new LinkedHashSet<>();
        for (Object value : ports) {
            if (!(value instanceof Map<?, ?> port) || !"OUTPUT".equals(asString(port.get("direction")))) {
                continue;
            }
            String id = asString(port.get("id"));
            String materialNodeId = asString(port.get("materialNodeId"));
            String materialKind = asString(port.get("materialKind"));
            Integer ordinal = asInteger(port.get("ordinal"));
            if (isBlank(id) || isBlank(materialNodeId) || isBlank(materialKind) || ordinal == null) {
                throw catalogMismatch(processNode,
                        "产出端口缺少 id、materialNodeId、materialKind 或 ordinal",
                        "请删除并重新添加该产出端口");
            }
            if (!portIds.add(id)) {
                throw catalogMismatch(processNode, "产出端口 ID 重复: " + id,
                        "请删除重复端口并重新添加产出");
            }
            if (!materialNodeIds.add(materialNodeId)) {
                throw catalogMismatch(processNode, "多个产出端口共用了同一个物料 Cell: " + materialNodeId,
                        "请让每个产出端口绑定独立物料 Cell");
            }
            if (!ordinals.add(ordinal)) {
                throw catalogMismatch(processNode, "产出端口 ordinal 重复: " + ordinal,
                        "请为每个产出端口设置唯一顺序");
            }
            outputPorts.add(new Port(id, materialNodeId, materialKind, ordinal));
        }
        return outputPorts;
    }

    private ProductProcessWorkflowDTO.Node resolveOutputMaterial(
            ProductProcessWorkflowDTO.Node processNode,
            Port outputPort,
            Map<String, ProductProcessWorkflowDTO.Node> nodesById) {
        ProductProcessWorkflowDTO.Node materialNode = nodesById.get(outputPort.materialNodeId());
        if (materialNode == null || "PROCESS".equals(materialNode.getKind())
                || "RAW_MATERIAL".equals(materialNode.getKind())) {
            throw catalogMismatch(processNode,
                    "产出端口 " + outputPort.id() + " 未指向有效产出物料 Cell",
                    "请将端口连接到半成品或成品 Cell");
        }
        return materialNode;
    }

    private Map<String, WorkProcess> indexWorkProcesses(
            String factoryId,
            Set<String> requestedIds,
            List<ProductProcessWorkflowDTO.Node> processNodes,
            List<WorkProcess> rows) {
        Map<String, WorkProcess> indexed = new LinkedHashMap<>();
        if (rows == null) {
            mismatch(processNodes.getFirst(), "工序目录批量查询返回空结果",
                    "请刷新工序目录后重试");
        }
        for (WorkProcess row : rows) {
            if (row == null || isBlank(row.getId())) {
                mismatch(processNodes.getFirst(), "工序目录返回了无效记录",
                        "请联系管理员检查工序目录数据");
            }
            if (!requestedIds.contains(row.getId())) {
                continue;
            }
            ProductProcessWorkflowDTO.Node processNode = processNodeFor(row.getId(), processNodes);
            if (!factoryId.equals(row.getFactoryId())) {
                mismatch(processNode, "工序不属于当前工厂: " + row.getId(),
                        "请重新选择当前工厂中的有效工序");
            }
            if (indexed.putIfAbsent(row.getId(), row) != null) {
                mismatch(processNode, "工序目录返回了重复记录: " + row.getId(),
                        "请联系管理员清理重复工序记录");
            }
        }
        for (String requestedId : requestedIds) {
            if (!indexed.containsKey(requestedId)) {
                ProductProcessWorkflowDTO.Node processNode = processNodeFor(requestedId, processNodes);
                mismatch(processNode, "引用的工序不属于当前工厂或已不存在: " + requestedId,
                        "请重新选择当前工厂中的有效工序");
            }
        }
        return indexed;
    }

    private Map<String, ProductType> indexProducts(
            String factoryId,
            Set<String> requestedIds,
            List<OutputBinding> outputBindings,
            List<ProductType> rows) {
        Map<String, ProductType> indexed = new LinkedHashMap<>();
        if (rows == null) {
            mismatch(outputBindings.getFirst().materialNode(), "SKU 目录批量查询返回空结果",
                    "请刷新 SKU 目录后重试");
        }
        for (ProductType row : rows) {
            if (row == null || isBlank(row.getId()) || !requestedIds.contains(row.getId())) {
                continue;
            }
            ProductProcessWorkflowDTO.Node materialNode = materialNodeFor(row.getId(), outputBindings);
            if (!factoryId.equals(row.getFactoryId())) {
                mismatch(materialNode, "产出 SKU 不属于当前工厂: " + row.getId(),
                        "请改选当前工厂的产出 SKU");
            }
            if (indexed.putIfAbsent(row.getId(), row) != null) {
                mismatch(materialNode, "SKU 目录返回了重复记录: " + row.getId(),
                        "请联系管理员清理重复 SKU 记录");
            }
        }
        for (String requestedId : requestedIds) {
            if (!indexed.containsKey(requestedId)) {
                ProductProcessWorkflowDTO.Node materialNode = materialNodeFor(requestedId, outputBindings);
                mismatch(materialNode, "产出 SKU 不存在: " + requestedId,
                        "请重新选择有效的产出 SKU");
            }
        }
        return indexed;
    }

    private ProductProcessWorkflowDTO.Node processNodeFor(
            String workProcessId,
            List<ProductProcessWorkflowDTO.Node> processNodes) {
        return processNodes.stream()
                .filter(node -> workProcessId.equals(asString(data(node).get("workProcessId"))))
                .findFirst()
                .orElse(processNodes.getFirst());
    }

    private ProductProcessWorkflowDTO.Node materialNodeFor(
            String skuId,
            List<OutputBinding> outputBindings) {
        return outputBindings.stream()
                .map(OutputBinding::materialNode)
                .filter(node -> skuId.equals(asString(data(node).get("skuId"))))
                .findFirst()
                .orElse(outputBindings.getFirst().materialNode());
    }

    private Map<String, Object> data(ProductProcessWorkflowDTO.Node node) {
        return node.getData() == null ? Map.of() : node.getData();
    }

    private String displayName(ProductProcessWorkflowDTO.Node node) {
        String name = asString(data(node).get("processName"));
        if (isBlank(name)) {
            name = asString(data(node).get("name"));
        }
        return isBlank(name) ? node.getId() : name;
    }

    private String displayCategory(String category) {
        return isBlank(category) ? "未分类" : category;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void mismatch(ProductProcessWorkflowDTO.Node node, String reason, String action) {
        throw catalogMismatch(node, reason, action);
    }

    private BusinessException catalogMismatch(
            ProductProcessWorkflowDTO.Node node,
            String reason,
            String action) {
        String cellName = displayName(node);
        return new BusinessException(400, "Cell「" + cellName + "」: " + reason)
                .withCode("PRODUCT_PROCESS_WORKFLOW_CATALOG_MISMATCH")
                .withHint("请处理 Cell「" + cellName + "」: " + action)
                .withSeverity("warning");
    }

    private record Port(String id, String materialNodeId, String materialKind, int ordinal) {}

    private record OutputBinding(ProductProcessWorkflowDTO.Node materialNode) {}
}
