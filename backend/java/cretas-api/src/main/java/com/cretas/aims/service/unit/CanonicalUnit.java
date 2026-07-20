package com.cretas.aims.service.unit;

import java.math.BigDecimal;
import java.util.Set;

public record CanonicalUnit(
        String code,
        UnitDimension dimension,
        String baseCode,
        BigDecimal factorToBase,
        String displayName,
        int displayScale,
        Set<UnitUsageScope> usageScopes,
        String conversionFamily,
        boolean active) {

    public CanonicalUnit(
            String code,
            UnitDimension dimension,
            String baseCode,
            BigDecimal factorToBase,
            String displayName,
            int displayScale) {
        this(code, dimension, baseCode, factorToBase, displayName, displayScale,
                Set.of(), baseCode, true);
    }
}
