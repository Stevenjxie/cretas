package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.UnitConversionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Normalizes a sales-order line into the finished SKU's production base unit. */
@Component
@RequiredArgsConstructor
public class SalesOrderPlanQuantityNormalizer {

    private final UnitConversionService unitConversionService;

    public PlanQuantity normalize(SalesOrderItem item, ProductType product) {
        return normalize(item == null ? null : item.getQuantity(), item, product);
    }

    /** Normalizes a partial/remaining sales quantity while keeping the line's immutable unit snapshot. */
    public PlanQuantity normalize(BigDecimal sourceQuantity, SalesOrderItem item, ProductType product) {
        String sourceUnit = trim(item == null ? null : item.getUnit());
        String targetUnit = trim(product == null ? null : product.getUnit());
        if (sourceQuantity == null || sourceQuantity.compareTo(BigDecimal.ZERO) <= 0
                || sourceUnit == null || targetUnit == null) {
            throw cannotConvert(sourceUnit, targetUnit);
        }

        BigDecimal normalized;
        if (sameUnit(sourceUnit, targetUnit)) {
            normalized = sourceQuantity;
        } else if (matchesPackagingSnapshot(item, sourceUnit, targetUnit)) {
            normalized = sourceQuantity.multiply(item.getPackagingFactor());
        } else {
            BigDecimal dimensional = unitConversionService.convert(sourceQuantity, sourceUnit, targetUnit);
            if (dimensional != null) {
                normalized = dimensional;
            } else {
                BigDecimal grams = unitConversionService.convert(sourceQuantity, sourceUnit, "g");
                BigDecimal gramsPerUnit = product.getGramsPerUnit();
                if (grams == null || gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
                    throw cannotConvert(sourceUnit, targetUnit);
                }
                normalized = grams.divide(gramsPerUnit, 6, RoundingMode.HALF_UP);
            }
        }

        return new PlanQuantity(normalized.stripTrailingZeros(), targetUnit, sourceQuantity, sourceUnit);
    }

    private static boolean matchesPackagingSnapshot(SalesOrderItem item, String sourceUnit, String targetUnit) {
        return item != null
                && sameUnit(sourceUnit, trim(item.getPackagingUnit()))
                && sameUnit(targetUnit, trim(item.getPackagingBaseUnit()))
                && item.getPackagingFactor() != null
                && item.getPackagingFactor().compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean sameUnit(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException cannotConvert(String sourceUnit, String targetUnit) {
        return new BusinessException(422,
                "销售订单单位无法换算为生产基本单位: " + sourceUnit + " -> " + targetUnit)
                .withCode("SALES_PLAN_UNIT_UNCONVERTIBLE")
                .withHint("请维护销售包装规格或成品单件克重后重试")
                .withHintTarget("unit");
    }

    public record PlanQuantity(
            BigDecimal quantity,
            String unit,
            BigDecimal displayQuantity,
            String displayUnit) {}
}
