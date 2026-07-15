package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authoritative reporting-unit projection for Workflow material ports.
 *
 * <p>Inventory may keep its historical storage unit. Production reporting is deliberately
 * narrower: raw materials and semi-finished goods are reported by kg, while a finished-good
 * output inherits the SKU's current base unit.</p>
 */
@Component
@RequiredArgsConstructor
public class WorkflowReportingUnitResolver {

    public static final String RAW_MATERIAL = "RAW_MATERIAL";
    public static final String SEMI_FINISHED = "SEMI_FINISHED";
    public static final String FINISHED_GOOD = "FINISHED_GOOD";

    private final ProductTypeRepository productTypeRepository;
    private final UnitContractService unitContractService;

    public String resolve(
            String factoryId,
            String materialKind,
            String skuId,
            String declaredUnit) {
        if (RAW_MATERIAL.equals(materialKind) || SEMI_FINISHED.equals(materialKind)) {
            return "kg";
        }
        if (FINISHED_GOOD.equals(materialKind)) {
            ProductType product = productTypeRepository.findByIdAndFactoryId(skuId, factoryId)
                    .orElseThrow(() -> unresolved(materialKind, skuId,
                            "finished-good SKU was not found in the factory"));
            return canonical(factoryId, product.getUnit(), materialKind, skuId);
        }
        return canonical(factoryId, declaredUnit, materialKind, skuId);
    }

    private String canonical(String factoryId, String rawUnit, String materialKind, String skuId) {
        if (rawUnit == null || rawUnit.isBlank()) {
            throw unresolved(materialKind, skuId, "reporting unit is missing");
        }
        var normalized = unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized()) {
            throw unresolved(materialKind, skuId,
                    "reporting unit is unknown or ambiguous: " + rawUnit);
        }
        return normalized.code();
    }

    private BusinessException unresolved(String materialKind, String skuId, String reason) {
        return new BusinessException(409,
                "Workflow reporting unit cannot be resolved for " + materialKind + " SKU " + skuId
                        + ": " + reason)
                .withCode("WORKFLOW_REPORTING_UNIT_UNRESOLVED")
                .withHint("Complete the SKU base-unit contract before publishing or reporting this Workflow")
                .withSeverity("BLOCKING")
                .withHintTarget("SKU unit");
    }
}
