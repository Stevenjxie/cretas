package com.cretas.aims.service.unit;

import java.math.BigDecimal;
import java.util.List;

public record UnitConversionResult(
        UnitConversionStatus status,
        BigDecimal quantity,
        String fromUnit,
        String toUnit,
        List<String> path,
        String conversionRefId,
        Long conversionVersion,
        String message) {

    public boolean succeeded() {
        return status == UnitConversionStatus.IDENTITY
                || status == UnitConversionStatus.CONVERTED;
    }
}
