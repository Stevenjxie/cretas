package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.WorkflowUnitIssueDTO;
import com.cretas.aims.dto.workflow.WorkflowUnitValidationResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigInteger;

/** Server-side authority for Workflow material/port/reporting unit contracts. */
@Component
@RequiredArgsConstructor
public class ProductProcessWorkflowUnitValidator {

    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductUnitConversionRepository conversionRepository;
    private final UnitContractService unitContractService;

    public WorkflowUnitValidationResult validate(String factoryId, ProductProcessWorkflowDTO definition) {
        List<WorkflowUnitIssueDTO> errors = new ArrayList<>();
        List<WorkflowUnitIssueDTO> warnings = new ArrayList<>();
        Map<String, ProductProcessWorkflowDTO.Node> materials = new HashMap<>();
        Set<String> productIds = new LinkedHashSet<>();
        Set<String> rawIds = new LinkedHashSet<>();

        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if ("PROCESS".equals(node.getKind())) continue;
            materials.put(node.getId(), node);
            String skuId = text(data(node).get("skuId"));
            if (blank(skuId)) {
                warnings.add(issue("SKU_UNIT_UNKNOWN", "物料 Cell 尚未绑定主数据单位", node.getId(), null, null, null));
            } else if ("RAW_MATERIAL".equals(node.getKind())) {
                rawIds.add(skuId);
            } else {
                productIds.add(skuId);
            }
        }

        Map<String, String> primaryUnits = loadPrimaryUnits(factoryId, productIds, rawIds);
        Map<String, ProductUnitConversion> conversionsById = loadReferencedConversions(definition);
        LocalDateTime now = LocalDateTime.now();
        for (ProductProcessWorkflowDTO.Node material : materials.values()) {
            String skuId = text(data(material).get("skuId"));
            if (blank(skuId)) continue;
            String expected = primaryUnits.get(primaryKey(material.getKind(), skuId));
            if (expected == null) {
                errors.add(issue("SKU_UNIT_UNKNOWN", "绑定的物料不存在、跨工厂或缺少主单位", material.getId(), null, null, null));
                continue;
            }
            String materialUnit = canonical(factoryId, text(data(material).get("baseUnit")));
            String expectedUnit = canonical(factoryId, expected);
            if (materialUnit == null || expectedUnit == null) {
                errors.add(issue("WORKFLOW_UNIT_UNKNOWN", "物料单位不是单位目录中的有效代码或别名",
                        material.getId(), null, text(data(material).get("baseUnit")), expected));
                continue;
            }
            if (!materialUnit.equals(expectedUnit)
                    && !isIntrinsicConversion(factoryId, skuId, materialUnit, expectedUnit, now)) {
                errors.add(issue("WORKFLOW_MATERIAL_UNIT_STALE", "物料 Cell 单位与绑定主数据单位不一致",
                        material.getId(), null, materialUnit, expectedUnit));
            }
        }

        for (ProductProcessWorkflowDTO.Node process : definition.getNodes()) {
            if (!"PROCESS".equals(process.getKind())) continue;
            List<Map<?, ?>> ports = ports(process);
            validatePrimaryHint(factoryId, process, ports, "INPUT", "inputUnit", errors);
            validatePrimaryHint(factoryId, process, ports, "OUTPUT", "outputUnit", errors);
            for (Map<?, ?> port : ports) {
                validatePort(factoryId, process, port, materials, primaryUnits, conversionsById, now, errors);
            }
        }
        for (String productId : productIds) {
            for (String graphError : unitContractService.validateConversionGraph(factoryId, productId, now)) {
                errors.add(issue("WORKFLOW_CONVERSION_GRAPH_INVALID", graphError, null, null, null, null));
            }
        }
        return new WorkflowUnitValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    public void validateForPublish(String factoryId, ProductProcessWorkflowDTO definition) {
        WorkflowUnitValidationResult result = validate(factoryId, definition);
        if (result.valid()) return;
        WorkflowUnitIssueDTO first = result.errors().getFirst();
        String location = (first.nodeId() == null ? "" : " node=" + first.nodeId())
                + (first.portId() == null ? "" : " port=" + first.portId());
        String units = first.currentUnit() == null && first.expectedUnit() == null
                ? ""
                : " current=" + first.currentUnit() + " expected=" + first.expectedUnit();
        throw new BusinessException(400, first.message() + location + units)
                .withCode("WORKFLOW_PORT_UNIT_STALE")
                .withHint("请在 Workflow 中重新绑定 SKU 单位或选择有效的换算关系后再发布")
                .withSeverity("warning");
    }

    private void validatePort(
            String factoryId,
            ProductProcessWorkflowDTO.Node process,
            Map<?, ?> port,
            Map<String, ProductProcessWorkflowDTO.Node> materials,
            Map<String, String> primaryUnits,
            Map<String, ProductUnitConversion> conversionsById,
            LocalDateTime now,
            List<WorkflowUnitIssueDTO> errors) {
        String portId = text(port.get("id"));
        ProductProcessWorkflowDTO.Node material = materials.get(text(port.get("materialNodeId")));
        if (material == null) return;
        String skuId = text(data(material).get("skuId"));
        String expectedRaw = primaryUnits.get(primaryKey(material.getKind(), skuId));
        String expected = canonical(factoryId, expectedRaw);
        String current = canonical(factoryId, text(port.get("unit")));
        if (current == null || expected == null) {
            errors.add(issue("WORKFLOW_UNIT_UNKNOWN", "工序端口单位无效", process.getId(), portId,
                    text(port.get("unit")), expectedRaw));
            return;
        }
        String refId = text(port.get("conversionRefId"));
        Long refVersion = number(port.get("conversionVersion"));
        if (current.equals(expected) && blank(refId) && refVersion == null) return;
        if (blank(refId) && refVersion == null
                && isIntrinsicConversion(factoryId, skuId, current, expected, now)) return;
        if (blank(refId) || refVersion == null) {
            errors.add(issue("WORKFLOW_CONVERSION_REQUIRED", "端口单位与物料主单位不同，必须绑定精确换算版本",
                    process.getId(), portId, current, expected));
            return;
        }
        ProductUnitConversion conversion = conversionsById.get(refId);
        if (conversion == null
                || !factoryId.equals(conversion.getFactoryId())
                || !skuId.equals(conversion.getProductTypeId())
                || !refVersion.equals(conversion.getVersion())
                || conversion.getEffectiveFrom().isAfter(now)
                || (conversion.getEffectiveTo() != null && !conversion.getEffectiveTo().isAfter(now))) {
            errors.add(issue("WORKFLOW_CONVERSION_STALE", "端口引用的换算关系已失效或版本不匹配",
                    process.getId(), portId, current, expected));
            return;
        }
        String from = canonical(factoryId, conversion.getFromUnitCode());
        String to = canonical(factoryId, conversion.getToUnitCode());
        if (!((current.equals(from) && expected.equals(to)) || (current.equals(to) && expected.equals(from)))) {
            errors.add(issue("WORKFLOW_CONVERSION_STALE", "端口引用的换算关系不适用于当前单位对",
                    process.getId(), portId, current, expected));
        }
    }

    private void validatePrimaryHint(
            String factoryId,
            ProductProcessWorkflowDTO.Node process,
            List<Map<?, ?>> ports,
            String direction,
            String hintField,
            List<WorkflowUnitIssueDTO> errors) {
        Map<?, ?> primary = ports.stream()
                .filter(port -> direction.equals(text(port.get("direction"))))
                .min(Comparator.comparingInt(port -> integer(port.get("ordinal"))))
                .orElse(null);
        if (primary == null) return;
        String hint = canonical(factoryId, text(data(process).get(hintField)));
        String portUnit = canonical(factoryId, text(primary.get("unit")));
        if (hint == null || portUnit == null || !hint.equals(portUnit)) {
            errors.add(issue("WORKFLOW_PROCESS_UNIT_STALE", "工序汇总单位与主端口单位不一致",
                    process.getId(), text(primary.get("id")), text(data(process).get(hintField)), text(primary.get("unit"))));
        }
    }

    private Map<String, String> loadPrimaryUnits(String factoryId, Set<String> productIds, Set<String> rawIds) {
        Map<String, String> result = new HashMap<>();
        for (ProductType row : productTypeRepository.findByIdIn(productIds)) {
            if (factoryId.equals(row.getFactoryId())) result.put(primaryKey("PRODUCT", row.getId()), row.getUnit());
        }
        for (RawMaterialType row : rawMaterialTypeRepository.findAllById(rawIds)) {
            if (factoryId.equals(row.getFactoryId())) result.put(primaryKey("RAW_MATERIAL", row.getId()), row.getUnit());
        }
        return result;
    }

    private Map<String, ProductUnitConversion> loadReferencedConversions(ProductProcessWorkflowDTO definition) {
        Set<String> ids = new LinkedHashSet<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!"PROCESS".equals(node.getKind())) continue;
            for (Map<?, ?> port : ports(node)) {
                String id = text(port.get("conversionRefId"));
                if (!blank(id)) ids.add(id);
            }
        }
        Map<String, ProductUnitConversion> result = new HashMap<>();
        for (ProductUnitConversion row : conversionRepository.findAllById(ids)) {
            result.put(row.getId(), row);
        }
        return result;
    }

    private String primaryKey(String kind, String id) {
        return ("RAW_MATERIAL".equals(kind) ? "RAW:" : "PRODUCT:") + id;
    }

    private List<Map<?, ?>> ports(ProductProcessWorkflowDTO.Node node) {
        Object raw = data(node).get("ports");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Map<?, ?> port) result.add(port);
        }
        return List.copyOf(result);
    }

    private Map<String, Object> data(ProductProcessWorkflowDTO.Node node) {
        return node.getData() == null ? Map.of() : node.getData();
    }

    private String canonical(String factoryId, String value) {
        UnitNormalizationResult normalized = unitContractService.normalize(factoryId, value);
        return normalized.recognized() ? normalized.code() : null;
    }

    private boolean isIntrinsicConversion(
            String factoryId,
            String skuId,
            String fromUnit,
            String toUnit,
            LocalDateTime at) {
        UnitConversionResult result = unitContractService.convert(new UnitConversionContext(
                factoryId,
                skuId,
                fromUnit,
                toUnit,
                at,
                UnitUsageScene.PRODUCTION,
                null,
                null));
        return result != null
                && result.succeeded()
                && result.conversionRefId() == null
                && result.steps().stream().allMatch(step -> step.conversionRefId() == null);
    }

    private WorkflowUnitIssueDTO issue(String code, String message, String nodeId, String portId,
                                       String current, String expected) {
        return new WorkflowUnitIssueDTO(code, message, nodeId, portId, current, expected);
    }

    private String text(Object value) { return value instanceof String text ? text : null; }
    private Long number(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
            return integer.longValue();
        }
        return null;
    }
    private int integer(Object value) { return value instanceof Number number ? number.intValue() : Integer.MAX_VALUE; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
