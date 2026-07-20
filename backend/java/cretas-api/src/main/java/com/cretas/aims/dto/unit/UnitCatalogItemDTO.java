package com.cretas.aims.dto.unit;

import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitUsageScope;

import java.util.Set;

public record UnitCatalogItemDTO(
        String code,
        String label,
        UnitDimension dimension,
        String baseCode,
        int displayScale,
        Set<UnitUsageScope> usageScopes,
        String conversionFamily,
        boolean active) {

    public static UnitCatalogItemDTO from(CanonicalUnit unit) {
        return new UnitCatalogItemDTO(
                unit.code(), unit.displayName(), unit.dimension(), unit.baseCode(), unit.displayScale(),
                unit.usageScopes(), unit.conversionFamily(), unit.active());
    }
}
