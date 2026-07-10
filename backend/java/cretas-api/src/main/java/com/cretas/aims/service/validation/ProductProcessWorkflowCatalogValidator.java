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

        LinkedHashSet<String> workProcessIds = new LinkedHashSet<>();
        for (ProductProcessWorkflowDTO.Node processNode : processNodes) {
            String workProcessId = asString(data(processNode).get("workProcessId"));
            if (isBlank(workProcessId)) {
                mismatch(processNode, "未绑定真实工序目录", "请重新选择该 Cell 的工序");
            }
            workProcessIds.add(workProcessId);
        }

        Map<String, WorkProcess> workProcessesById = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, new ArrayList<>(workProcessIds))
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));

        List<OutputBinding> outputBindings = new ArrayList<>();
        for (ProductProcessWorkflowDTO.Node processNode : processNodes) {
            String workProcessId = asString(data(processNode).get("workProcessId"));
            WorkProcess workProcess = workProcessesById.get(workProcessId);
            if (workProcess == null) {
                mismatch(processNode, "引用的工序不属于当前工厂或已不存在: " + workProcessId,
                        "请重新选择当前工厂中的有效工序");
            }

            List<Port> outputPorts = outputPorts(processNode);
            for (Port outputPort : outputPorts) {
                ProductProcessWorkflowDTO.Node materialNode = resolveOutputMaterial(
                        processNode, outputPort, definition.getEdges(), nodesById);
                if (!materialNode.getKind().equals(outputPort.materialKind())) {
                    mismatch(materialNode,
                            "产出端口类型 " + outputPort.materialKind()
                                    + " 与物料 Cell 类型 " + materialNode.getKind() + " 不一致",
                            "请在工序 Cell 中重新绑定该产出物料");
                }
                outputBindings.add(new OutputBinding(materialNode, outputPort));
            }

            OutputBinding primaryOutput = outputPorts.stream()
                    .min(Comparator.comparingInt(Port::ordinal))
                    .map(port -> new OutputBinding(
                            resolveOutputMaterial(processNode, port, definition.getEdges(), nodesById), port))
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

        Map<String, ProductType> productsById = productTypeRepository.findByIdIn(new ArrayList<>(skuIds)).stream()
                .collect(Collectors.toMap(ProductType::getId, Function.identity()));
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
            outputPorts.add(new Port(id, materialNodeId, materialKind, ordinal));
        }
        return outputPorts;
    }

    private ProductProcessWorkflowDTO.Node resolveOutputMaterial(
            ProductProcessWorkflowDTO.Node processNode,
            Port outputPort,
            List<ProductProcessWorkflowDTO.Edge> edges,
            Map<String, ProductProcessWorkflowDTO.Node> nodesById) {
        List<ProductProcessWorkflowDTO.Edge> portEdges = edges.stream()
                .filter(edge -> processNode.getId().equals(edge.getSource())
                        && outputPort.id().equals(edge.getSourceHandle()))
                .toList();
        if (portEdges.size() != 1 || !outputPort.materialNodeId().equals(portEdges.getFirst().getTarget())) {
            throw catalogMismatch(processNode,
                    "产出端口 " + outputPort.id() + " 与目标物料 Cell 绑定不一致",
                    "请删除并重新连接该产出端口");
        }
        ProductProcessWorkflowDTO.Node materialNode = nodesById.get(outputPort.materialNodeId());
        if (materialNode == null || "PROCESS".equals(materialNode.getKind())
                || "RAW_MATERIAL".equals(materialNode.getKind())) {
            throw catalogMismatch(processNode,
                    "产出端口 " + outputPort.id() + " 未指向有效产出物料 Cell",
                    "请将端口连接到半成品或成品 Cell");
        }
        return materialNode;
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

    private record OutputBinding(ProductProcessWorkflowDTO.Node materialNode, Port port) {}
}
