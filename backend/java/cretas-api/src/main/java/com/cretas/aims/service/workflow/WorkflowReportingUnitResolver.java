package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
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
     * 质量单位一律归一到 kg（g / 千克 / 公斤 → kg），其余量纲**原样保留用户配的写法**。
     *
     * <p>这里刻意不返回契约规范码。单位契约把 只 / 个 / 件 / pcs 视作同一个计数单位
     * {@code pcs}，规范名是「件」—— 拿它当端口单位存下来，用户在工序里配的「只」
     * 到了报工页就变成「件」，看起来像是系统改了他的配置。等价性由匹配环节负责
     * （分配服务对投料与批次单位各做一次 normalize），显示环节不需要也不应该改写它。</p>
     *
     * <p>质量单位是例外：g / 千克 / 公斤 统一成 kg，免得同一条链上半段记 g、下半段记
     * 千克。这一条是既有约定，保持不变。</p>
     */
    private String massAwareUnit(String factoryId, String materialKind, String skuId, String rawUnit) {
        // canonical 仍然要跑：它负责校验这个单位是否被契约认识，不认识就 fail closed
        String code = canonical(factoryId, rawUnit, materialKind, skuId);
        boolean mass = unitContractService.describe(factoryId, code)
                .filter(unit -> unit.dimension() == UnitDimension.MASS)
                .isPresent();
        return mass ? MASS_REPORTING_UNIT : rawUnit.trim();
    }

    /**
     * 端口的等价码 —— **只有科学单位才有**。
     *
     * <p>质量与体积之间存在恒定换算，归一到等价码有物理意义。计数与包装单位没有：
     * 只 / 件 / 袋 / 盒 之间不存在普适换算，硬编一个共同等价码等于让系统替工厂断定
     * 两个不同的东西相同；工厂新建单位时也无从判断该挂进哪个族。</p>
     *
     * <p>因此非科学单位的等价码就是它自己 —— 写法相同才是同一个单位。</p>
     */
    public String canonicalCode(String factoryId, String reportingUnit) {
        if (reportingUnit == null || reportingUnit.isBlank()) return null;
        String trimmed = reportingUnit.trim();
        // 同理: 契约认不出的写法折大小写后再当等价码, 避免 KG / kg 被当成两个单位
        return unitContractService.describe(factoryId, trimmed)
                .filter(unit -> unit.dimension() == UnitDimension.MASS
                        || unit.dimension() == UnitDimension.VOLUME)
                .map(CanonicalUnit::code)
                .orElseGet(() -> trimmed.toLowerCase(java.util.Locale.ROOT));
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
