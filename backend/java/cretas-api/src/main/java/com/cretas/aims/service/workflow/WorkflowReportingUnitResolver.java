package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authoritative reporting-unit projection for Workflow material ports.
 *
 * <p>Inventory may keep its historical storage unit. Production reporting is narrower:
 * every material reports in its own base unit, and mass units collapse to kg so that a
 * factory never has to reconcile g against 千克 mid-chain.</p>
 *
 * <p>Raw materials and semi-finished goods used to be pinned to kg unconditionally. That
 * held only as long as every upstream material was weighed. A factory processing whole
 * chickens counts them by 只 — forcing kg silently discarded the unit on both the port and
 * the material-primary-unit projection, so no conversion was even recorded (both sides read
 * kg, factor 1.0). The reporting screen then showed kg for a material the operator had
 * correctly configured as 只, with nothing in the UI explaining where the unit went.</p>
 */
@Component
@RequiredArgsConstructor
public class WorkflowReportingUnitResolver {

    public static final String RAW_MATERIAL = "RAW_MATERIAL";
    public static final String SEMI_FINISHED = "SEMI_FINISHED";
    public static final String FINISHED_GOOD = "FINISHED_GOOD";

    private static final String MASS_REPORTING_UNIT = "kg";

    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final UnitContractService unitContractService;

    public String resolve(
            String factoryId,
            String materialKind,
            String skuId,
            String declaredUnit) {
        if (RAW_MATERIAL.equals(materialKind)) {
            return massAwareUnit(factoryId, materialKind, skuId,
                    rawMaterialUnit(factoryId, skuId, declaredUnit));
        }
        if (SEMI_FINISHED.equals(materialKind) || FINISHED_GOOD.equals(materialKind)) {
            ProductType product = productTypeRepository.findByIdAndFactoryId(skuId, factoryId)
                    .orElseThrow(() -> unresolved(materialKind, skuId,
                            "SKU was not found in the factory"));
            return massAwareUnit(factoryId, materialKind, skuId, product.getUnit());
        }
        return canonical(factoryId, declaredUnit, materialKind, skuId);
    }

    /**
     * 原料主数据不在 product_types 里；查不到时回落到画布声明的单位，
     * 而不是硬塞 kg —— 静默换单位正是本次事故的成因。
     */
    private String rawMaterialUnit(String factoryId, String skuId, String declaredUnit) {
        if (skuId == null || skuId.isBlank()) return declaredUnit;
        return rawMaterialTypeRepository.findByIdAndFactoryId(skuId, factoryId)
                .map(RawMaterialType::getUnit)
                .filter(unit -> unit != null && !unit.isBlank())
                .orElse(declaredUnit);
    }

    /**
     * 质量单位一律归一到 kg（g / 千克 / 公斤 → kg），其余量纲保留物料自身单位。
     * 量纲判不出来时保留原单位：宁可原样透传，也不要再猜一次。
     */
    private String massAwareUnit(String factoryId, String materialKind, String skuId, String rawUnit) {
        String code = canonical(factoryId, rawUnit, materialKind, skuId);
        return unitContractService.describe(factoryId, code)
                .filter(unit -> unit.dimension() == UnitDimension.MASS)
                .map(unit -> MASS_REPORTING_UNIT)
                .orElse(code);
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
