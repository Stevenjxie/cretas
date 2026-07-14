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
        String message,
        List<UnitConversionStep> steps) {

    public UnitConversionResult {
        path = path == null ? List.of() : List.copyOf(path);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public UnitConversionResult(
            UnitConversionStatus status,
            BigDecimal quantity,
            String fromUnit,
            String toUnit,
            List<String> path,
            String conversionRefId,
            Long conversionVersion,
            String message) {
        this(status, quantity, fromUnit, toUnit, path, conversionRefId, conversionVersion, message, List.of());
    }

    public boolean succeeded() {
        return status == UnitConversionStatus.IDENTITY
                || status == UnitConversionStatus.CONVERTED;
    }
}
