package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Normalizes a sales-order line into the finished SKU's production base unit.
 *
 * <p>⚠️ Unit identity is decided by {@link UnitContractService}, never by string equality.
 * {@code case} / {@code box} are catalog aliases of {@code 箱} / {@code 盒}: a sales line imported
 * with the English writing and a packaging snapshot stored with the Chinese code name the same unit.
 * Comparing them raw rejected SO-20260817-0001 even though its snapshot (1 箱 = 8 盒) was correct.
 *
 * <p>⚠️ Product-specific conversions ({@code product_unit_conversions}, kept in sync from the SKU's
 * packaging coefficient and net weight by {@code ProductSpecificationConversionSyncService}) are only
 * reachable when factoryId + productTypeId + business time are supplied. Passing null for them — as
 * the legacy {@code UnitConversionService} facade does — makes the contract bail out with
 * PRODUCT_CONVERSION_MISSING before it ever loads the graph, so a fully-configured SKU still failed.
 */
@Component
@RequiredArgsConstructor
public class SalesOrderPlanQuantityNormalizer {

    private final UnitContractService unitContractService;

    public PlanQuantity normalize(SalesOrderItem item, ProductType product) {
        return normalize(item == null ? null : item.getQuantity(), item, product);
    }

    /** Normalizes a partial/remaining sales quantity while keeping the line's immutable unit snapshot. */
    public PlanQuantity normalize(BigDecimal sourceQuantity, SalesOrderItem item, ProductType product) {
        String sourceUnit = trim(item == null ? null : item.getUnit());
        String targetUnit = trim(product == null ? null : product.getUnit());
        String factoryId = product == null ? null : product.getFactoryId();
        String productTypeId = product == null ? null : product.getId();
        if (sourceQuantity == null || sourceQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw quantityUnusable(product, sourceQuantity, sourceUnit);
        }
        if (sourceUnit == null || targetUnit == null) {
            throw cannotConvert(product, sourceUnit, targetUnit);
        }

        BigDecimal normalized;
        if (sameUnit(factoryId, sourceUnit, targetUnit)) {
            normalized = sourceQuantity;
        } else if (matchesPackagingSnapshot(factoryId, item, sourceUnit, targetUnit)) {
            normalized = sourceQuantity.multiply(item.getPackagingFactor());
        } else {
            BigDecimal dimensional = convert(
                    sourceQuantity, factoryId, productTypeId, sourceUnit, targetUnit);
            if (dimensional != null) {
                normalized = dimensional;
            } else {
                BigDecimal grams = convert(sourceQuantity, factoryId, productTypeId, sourceUnit, "g");
                BigDecimal gramsPerUnit = product.getGramsPerUnit();
                if (grams == null || gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
                    throw cannotConvert(product, sourceUnit, targetUnit);
                }
                normalized = grams.divide(gramsPerUnit, 6, RoundingMode.HALF_UP);
            }
        }

        return new PlanQuantity(normalized.stripTrailingZeros(), targetUnit, sourceQuantity, sourceUnit);
    }

    /**
     * Runs the authoritative conversion with the full product context so the SKU's own conversion
     * graph is in scope. Returns null when the contract cannot decide — callers must fail loud.
     */
    private BigDecimal convert(
            BigDecimal quantity, String factoryId, String productTypeId, String fromUnit, String toUnit) {
        if (quantity == null || fromUnit == null || toUnit == null) return null;
        UnitConversionResult result = unitContractService.convert(quantity, new UnitConversionContext(
                factoryId, productTypeId, fromUnit, toUnit, LocalDateTime.now(), null, 6, RoundingMode.HALF_UP));
        return result.succeeded() && result.quantity() != null
                ? result.quantity().setScale(6, RoundingMode.HALF_UP)
                : null;
    }

    private boolean matchesPackagingSnapshot(
            String factoryId, SalesOrderItem item, String sourceUnit, String targetUnit) {
        return item != null
                && sameUnit(factoryId, sourceUnit, trim(item.getPackagingUnit()))
                && sameUnit(factoryId, targetUnit, trim(item.getPackagingBaseUnit()))
                && item.getPackagingFactor() != null
                && item.getPackagingFactor().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Raw equality stays as a fallback so factory free-text units the catalog cannot resolve keep
     * behaving as before; the catalog check only ever widens what counts as the same unit.
     */
    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right) || unitContractService.areEquivalent(factoryId, left, right);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException quantityUnusable(
            ProductType product, BigDecimal sourceQuantity, String sourceUnit) {
        return new BusinessException(422,
                "销售订单行的可排产数量不可用: " + (sourceQuantity == null ? "未填写" : sourceQuantity.toPlainString())
                        + (sourceUnit == null ? "" : " " + sourceUnit) + skuSuffix(product))
                .withCode("SALES_PLAN_QUANTITY_UNUSABLE")
                .withHint("请核对该产品行的订单数量与已交付/已排产数量")
                .withHintTarget("quantity");
    }

    /**
     * ⚠️ The hint must name the SKU and the units that actually failed. The previous wording
     * ("请维护销售包装规格或成品单件克重后重试") was wrong for SO-20260817-0001: both were already
     * maintained on that SKU, and following it would have had the user edit correct master data.
     */
    private static BusinessException cannotConvert(
            ProductType product, String sourceUnit, String targetUnit) {
        return new BusinessException(422,
                "销售订单单位无法换算为生产基本单位: " + sourceUnit + " -> " + targetUnit + skuSuffix(product))
                .withCode("SALES_PLAN_UNIT_UNCONVERTIBLE")
                .withHint("请到成品档案(SKU)为该产品补充 " + sourceUnit + " 与 " + targetUnit
                        + " 的换算关系: 维护包装规格(上级单位 + 每箱数量)或单件克重; "
                        + "也可在销售订单行上重新选择带换算系数的包装规格")
                .withHintTarget("unit");
    }

    private static String skuSuffix(ProductType product) {
        if (product == null) {
            return "";
        }
        String code = trim(product.getCode());
        String name = trim(product.getName());
        if (code == null && name == null) {
            return "";
        }
        return " (成品: " + (code == null ? "" : code) + (code != null && name != null ? " " : "")
                + (name == null ? "" : name) + ")";
    }

    public record PlanQuantity(
            BigDecimal quantity,
            String unit,
            BigDecimal displayQuantity,
            String displayUnit) {}
}
