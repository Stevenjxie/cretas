package com.cretas.aims.service.unit.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.unit.UnitGovernanceConflictDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitGovernanceAuditService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class UnitGovernanceAuditServiceImpl implements UnitGovernanceAuditService {

    private static final String KNOWN_CANONICAL_UNIT = "known canonical unit";
    private static final String EXPLICIT_NET_CONTENT = "effective NET_CONTENT conversion matching gramsPerUnit";

    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductUnitConversionRepository conversionRepository;
    private final UnitContractService unitContractService;
    private final ObjectMapper objectMapper;

    public UnitGovernanceAuditServiceImpl(
            ProductTypeRepository productTypeRepository,
            RawMaterialTypeRepository rawMaterialTypeRepository,
            ProductProcessWorkflowRepository workflowRepository,
            ProductUnitConversionRepository conversionRepository,
            UnitContractService unitContractService,
            ObjectMapper objectMapper) {
        this.productTypeRepository = productTypeRepository;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
        this.workflowRepository = workflowRepository;
        this.conversionRepository = conversionRepository;
        this.unitContractService = unitContractService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitGovernanceConflictDTO> scan(String factoryId) {
        LocalDateTime now = LocalDateTime.now();
        List<ProductType> products = productTypeRepository.findByFactoryId(factoryId);
        List<RawMaterialType> rawMaterials = rawMaterialTypeRepository.findByFactoryId(factoryId);
        List<ProductProcessWorkflow> workflows =
                workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(factoryId);
        List<ProductUnitConversion> conversions =
                conversionRepository.findByFactoryIdOrderByProductTypeIdAscCreatedAtAsc(factoryId);

        Map<String, ProductType> productsById = indexProducts(products);
        Map<String, RawMaterialType> rawById = indexRawMaterials(rawMaterials);
        Map<String, ProductUnitConversion> conversionsById = new HashMap<>();
        conversions.forEach(row -> conversionsById.put(row.getId(), row));

        List<UnitGovernanceConflictDTO> findings = new ArrayList<>();
        scanMasterData(factoryId, products, rawMaterials, conversions, now, findings);
        for (ProductProcessWorkflow workflow : workflows) {
            scanWorkflow(factoryId, workflow, productsById, rawById, conversionsById, now, findings);
        }
        return findings.stream()
                .collect(LinkedHashMap<String, UnitGovernanceConflictDTO>::new,
                        (result, finding) -> result.putIfAbsent(key(finding), finding),
                        LinkedHashMap::putAll)
                .values().stream()
                .sorted(conflictOrder())
                .toList();
    }

    private void scanMasterData(
            String factoryId,
            List<ProductType> products,
            List<RawMaterialType> rawMaterials,
            List<ProductUnitConversion> conversions,
            LocalDateTime now,
            List<UnitGovernanceConflictDTO> findings) {
        for (ProductType product : products) {
            canonicalOrFinding(factoryId, product.getId(), null, null, null,
                    product.getUnit(), findings);
            if (product.getGramsPerUnit() != null
                    && !hasExplicitNetContent(factoryId, product, conversions, now)) {
                findings.add(finding(factoryId, product.getId(), null, null, null,
                        String.valueOf(product.getGramsPerUnit()), EXPLICIT_NET_CONTENT,
                        "LEGACY_GRAMS_PER_UNIT_AMBIGUOUS"));
            }
        }
        for (RawMaterialType raw : rawMaterials) {
            canonicalOrFinding(factoryId, raw.getId(), null, null, null,
                    raw.getUnit(), findings);
        }
        for (ProductUnitConversion conversion : conversions) {
            canonicalOrFinding(factoryId, conversion.getProductTypeId(), null, null, null,
                    conversion.getFromUnitCode(), findings);
            canonicalOrFinding(factoryId, conversion.getProductTypeId(), null, null, null,
                    conversion.getToUnitCode(), findings);
        }
    }

    private void scanWorkflow(
            String factoryId,
            ProductProcessWorkflow workflow,
            Map<String, ProductType> productsById,
            Map<String, RawMaterialType> rawById,
            Map<String, ProductUnitConversion> conversionsById,
            LocalDateTime now,
            List<UnitGovernanceConflictDTO> findings) {
        List<ProductProcessWorkflowDTO.Node> nodes;
        try {
            nodes = objectMapper.readValue(workflow.getNodesJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            findings.add(finding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                    null, null, "invalid nodes_json", "valid Workflow JSON", "WORKFLOW_DEFINITION_INVALID"));
            return;
        }

        Map<String, ProductProcessWorkflowDTO.Node> materials = new HashMap<>();
        for (ProductProcessWorkflowDTO.Node node : nodes) {
            if (!"PROCESS".equals(node.getKind())) {
                materials.put(node.getId(), node);
                scanMaterial(factoryId, workflow, node, productsById, rawById, findings);
            }
        }
        for (ProductProcessWorkflowDTO.Node node : nodes) {
            if ("PROCESS".equals(node.getKind())) {
                scanProcess(factoryId, workflow, node, materials, productsById, rawById,
                        conversionsById, now, findings);
            }
        }
    }

    private void scanMaterial(
            String factoryId,
            ProductProcessWorkflow workflow,
            ProductProcessWorkflowDTO.Node material,
            Map<String, ProductType> productsById,
            Map<String, RawMaterialType> rawById,
            List<UnitGovernanceConflictDTO> findings) {
        String skuId = text(data(material).get("skuId"));
        String currentRaw = text(data(material).get("baseUnit"));
        String current = canonicalOrFinding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                material.getId(), null, currentRaw, findings);
        String expectedRaw = primaryUnit(material.getKind(), skuId, productsById, rawById);
        if (blank(skuId) || expectedRaw == null) {
            findings.add(finding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                    material.getId(), null, currentRaw, "bound SKU/material primary unit",
                    "MATERIAL_SKU_UNIT_MISMATCH"));
            return;
        }
        String expected = canonical(factoryId, expectedRaw);
        if (current != null && expected != null && !current.equals(expected)) {
            findings.add(finding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                    material.getId(), null, current, expected, "MATERIAL_SKU_UNIT_MISMATCH"));
        }
    }

    private void scanProcess(
            String factoryId,
            ProductProcessWorkflow workflow,
            ProductProcessWorkflowDTO.Node process,
            Map<String, ProductProcessWorkflowDTO.Node> materials,
            Map<String, ProductType> productsById,
            Map<String, RawMaterialType> rawById,
            Map<String, ProductUnitConversion> conversionsById,
            LocalDateTime now,
            List<UnitGovernanceConflictDTO> findings) {
        List<Map<?, ?>> ports = ports(process);
        scanPrimaryHint(factoryId, workflow, process, ports, "INPUT", "inputUnit", findings);
        scanPrimaryHint(factoryId, workflow, process, ports, "OUTPUT", "outputUnit", findings);
        for (Map<?, ?> port : ports) {
            scanPort(factoryId, workflow, process, port, materials, productsById, rawById,
                    conversionsById, now, findings);
        }
    }

    private void scanPrimaryHint(
            String factoryId,
            ProductProcessWorkflow workflow,
            ProductProcessWorkflowDTO.Node process,
            List<Map<?, ?>> ports,
            String direction,
            String hintField,
            List<UnitGovernanceConflictDTO> findings) {
        Map<?, ?> primary = ports.stream()
                .filter(port -> direction.equals(text(port.get("direction"))))
                .min(Comparator.comparingInt(port -> integer(port.get("ordinal"))))
                .orElse(null);
        if (primary == null) return;
        String hintRaw = text(data(process).get(hintField));
        String portRaw = text(primary.get("unit"));
        String hint = canonicalOrFinding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                process.getId(), text(primary.get("id")), hintRaw, findings);
        String port = canonicalOrFinding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                process.getId(), text(primary.get("id")), portRaw, findings);
        if (hint != null && port != null && !hint.equals(port)) {
            findings.add(finding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                    process.getId(), text(primary.get("id")), hint, port,
                    "PROCESS_PRIMARY_PORT_UNIT_MISMATCH"));
        }
    }

    private void scanPort(
            String factoryId,
            ProductProcessWorkflow workflow,
            ProductProcessWorkflowDTO.Node process,
            Map<?, ?> port,
            Map<String, ProductProcessWorkflowDTO.Node> materials,
            Map<String, ProductType> productsById,
            Map<String, RawMaterialType> rawById,
            Map<String, ProductUnitConversion> conversionsById,
            LocalDateTime now,
            List<UnitGovernanceConflictDTO> findings) {
        String portId = text(port.get("id"));
        ProductProcessWorkflowDTO.Node material = materials.get(text(port.get("materialNodeId")));
        if (material == null) return;
        String skuId = text(data(material).get("skuId"));
        String expectedRaw = primaryUnit(material.getKind(), skuId, productsById, rawById);
        String currentRaw = text(port.get("unit"));
        String current = canonicalOrFinding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                process.getId(), portId, currentRaw, findings);
        String expected = canonical(factoryId, expectedRaw);
        if (current == null || expected == null) return;

        String refId = text(port.get("conversionRefId"));
        Long refVersion = exactLong(port.get("conversionVersion"));
        if (current.equals(expected) && blank(refId) && refVersion == null) return;
        if (blank(refId) || refVersion == null) {
            findings.add(finding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                    process.getId(), portId, current, expected, "PORT_CONVERSION_REQUIRED"));
            return;
        }
        ProductUnitConversion conversion = conversionsById.get(refId);
        if (!validConversion(factoryId, skuId, current, expected, refVersion, conversion, now)) {
            findings.add(finding(factoryId, workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                    process.getId(), portId, current, expected, "PORT_CONVERSION_STALE"));
        }
    }

    private boolean validConversion(
            String factoryId,
            String skuId,
            String current,
            String expected,
            Long refVersion,
            ProductUnitConversion conversion,
            LocalDateTime now) {
        if (conversion == null
                || !factoryId.equals(conversion.getFactoryId())
                || !Objects.equals(skuId, conversion.getProductTypeId())
                || !refVersion.equals(conversion.getVersion())
                || conversion.getEffectiveFrom() == null
                || conversion.getEffectiveFrom().isAfter(now)
                || (conversion.getEffectiveTo() != null && !conversion.getEffectiveTo().isAfter(now))) {
            return false;
        }
        String from = canonical(factoryId, conversion.getFromUnitCode());
        String to = canonical(factoryId, conversion.getToUnitCode());
        return (current.equals(from) && expected.equals(to))
                || (current.equals(to) && expected.equals(from));
    }

    private boolean hasExplicitNetContent(
            String factoryId,
            ProductType product,
            List<ProductUnitConversion> conversions,
            LocalDateTime now) {
        BigDecimal grams = product.getGramsPerUnit();
        String primary = canonical(factoryId, product.getUnit());
        if (grams == null || grams.signum() <= 0 || primary == null || "g".equals(primary)) return false;
        for (ProductUnitConversion conversion : conversions) {
            if (!product.getId().equals(conversion.getProductTypeId())
                    || conversion.getSourceType() != ProductUnitConversion.SourceType.NET_CONTENT
                    || conversion.getFactor() == null
                    || conversion.getEffectiveFrom() == null
                    || conversion.getEffectiveFrom().isAfter(now)
                    || (conversion.getEffectiveTo() != null && !conversion.getEffectiveTo().isAfter(now))) {
                continue;
            }
            String from = canonical(factoryId, conversion.getFromUnitCode());
            String to = canonical(factoryId, conversion.getToUnitCode());
            if (primary.equals(from) && "g".equals(to)
                    && conversion.getFactor().compareTo(grams) == 0) return true;
            if ("g".equals(from) && primary.equals(to)) {
                BigDecimal inverse = BigDecimal.ONE.divide(grams, MathContext.DECIMAL128);
                if (conversion.getFactor().compareTo(inverse) == 0) return true;
            }
        }
        return false;
    }

    private String primaryUnit(
            String kind,
            String skuId,
            Map<String, ProductType> productsById,
            Map<String, RawMaterialType> rawById) {
        if (blank(skuId)) return null;
        if ("RAW_MATERIAL".equals(kind)) {
            RawMaterialType raw = rawById.get(skuId);
            return raw == null ? null : raw.getUnit();
        }
        ProductType product = productsById.get(skuId);
        return product == null ? null : product.getUnit();
    }

    private String canonicalOrFinding(
            String factoryId,
            String productTypeId,
            Integer workflowVersion,
            String nodeId,
            String portId,
            String raw,
            List<UnitGovernanceConflictDTO> findings) {
        String canonical = canonical(factoryId, raw);
        if (canonical == null) {
            findings.add(finding(factoryId, productTypeId, workflowVersion, nodeId, portId,
                    raw, KNOWN_CANONICAL_UNIT, "UNKNOWN_UNIT_ALIAS"));
        }
        return canonical;
    }

    private String canonical(String factoryId, String raw) {
        if (blank(raw)) return null;
        UnitNormalizationResult normalized = unitContractService.normalize(factoryId, raw);
        return normalized.recognized() ? normalized.code() : null;
    }

    private Map<String, ProductType> indexProducts(List<ProductType> rows) {
        Map<String, ProductType> result = new HashMap<>();
        rows.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private Map<String, RawMaterialType> indexRawMaterials(List<RawMaterialType> rows) {
        Map<String, RawMaterialType> result = new HashMap<>();
        rows.forEach(row -> result.put(row.getId(), row));
        return result;
    }

    private Map<String, Object> data(ProductProcessWorkflowDTO.Node node) {
        return node.getData() == null ? Map.of() : node.getData();
    }

    private List<Map<?, ?>> ports(ProductProcessWorkflowDTO.Node node) {
        Object raw = data(node).get("ports");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> port) result.add(port);
        }
        return result;
    }

    private UnitGovernanceConflictDTO finding(
            String factoryId,
            String productTypeId,
            Integer workflowVersion,
            String nodeId,
            String portId,
            String current,
            String expected,
            String errorCode) {
        return new UnitGovernanceConflictDTO(
                factoryId, productTypeId, workflowVersion, nodeId, portId,
                current, expected, errorCode);
    }

    private Comparator<UnitGovernanceConflictDTO> conflictOrder() {
        return Comparator.comparing(UnitGovernanceConflictDTO::productTypeId, Comparator.nullsFirst(String::compareTo))
                .thenComparing(UnitGovernanceConflictDTO::workflowVersion, Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(UnitGovernanceConflictDTO::nodeId, Comparator.nullsFirst(String::compareTo))
                .thenComparing(UnitGovernanceConflictDTO::portId, Comparator.nullsFirst(String::compareTo))
                .thenComparing(UnitGovernanceConflictDTO::errorCode);
    }

    private String key(UnitGovernanceConflictDTO finding) {
        return String.join("|",
                String.valueOf(finding.productTypeId()),
                String.valueOf(finding.workflowVersion()),
                String.valueOf(finding.nodeId()),
                String.valueOf(finding.portId()),
                String.valueOf(finding.current()),
                String.valueOf(finding.expected()),
                finding.errorCode());
    }

    private String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        return Integer.MAX_VALUE;
    }

    private Long exactLong(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
            return integer.longValue();
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
