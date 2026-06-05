package com.cretas.aims.util;

import java.math.BigDecimal;

public final class ProductionReportQuantityUtils {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private ProductionReportQuantityUtils() {
    }

    public static BigDecimal effectiveInputQuantity(
            BigDecimal inputQuantity,
            BigDecimal warehouseOutQuantity,
            BigDecimal feedInQuantity,
            BigDecimal carryoverQuantity) {
        if (feedInQuantity != null) {
            return feedInQuantity;
        }
        if (inputQuantity != null) {
            return inputQuantity;
        }
        if (warehouseOutQuantity != null && carryoverQuantity != null) {
            BigDecimal inferred = warehouseOutQuantity.subtract(carryoverQuantity);
            return inferred.compareTo(ZERO) > 0 ? inferred : ZERO;
        }
        return null;
    }

    public static BigDecimal effectiveCarryoverQuantity(
            BigDecimal warehouseOutQuantity,
            BigDecimal feedInQuantity,
            BigDecimal carryoverQuantity) {
        if (carryoverQuantity != null) {
            return carryoverQuantity;
        }
        if (warehouseOutQuantity == null || feedInQuantity == null) {
            return null;
        }
        BigDecimal remainder = warehouseOutQuantity.subtract(feedInQuantity);
        return remainder.compareTo(ZERO) > 0 ? remainder : ZERO;
    }

    public static boolean hasMaterialQuantities(
            BigDecimal inputQuantity,
            BigDecimal warehouseOutQuantity,
            BigDecimal feedInQuantity,
            BigDecimal carryoverQuantity) {
        return inputQuantity != null
                || warehouseOutQuantity != null
                || feedInQuantity != null
                || carryoverQuantity != null;
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(ZERO) < 0;
    }
}
