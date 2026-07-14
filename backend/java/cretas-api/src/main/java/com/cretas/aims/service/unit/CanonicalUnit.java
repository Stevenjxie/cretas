package com.cretas.aims.service.unit;

import java.math.BigDecimal;

public record CanonicalUnit(
        String code,
        UnitDimension dimension,
        String baseCode,
        BigDecimal factorToBase,
        String displayName,
        int displayScale) {
}
