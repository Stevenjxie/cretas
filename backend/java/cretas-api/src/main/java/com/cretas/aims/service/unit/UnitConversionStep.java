package com.cretas.aims.service.unit;

import java.math.BigDecimal;

/** One auditable edge in a unit conversion path. */
public record UnitConversionStep(
        String fromUnit,
        String toUnit,
        BigDecimal factor,
        String conversionRefId,
        Long conversionVersion) {
}
