package com.cretas.aims.service.unit;

import java.math.RoundingMode;
import java.time.LocalDateTime;

public record UnitConversionContext(
        String factoryId,
        String productTypeId,
        String fromUnit,
        String toUnit,
        LocalDateTime at,
        UnitUsageScene scene,
        Integer scale,
        RoundingMode roundingMode) {
}
