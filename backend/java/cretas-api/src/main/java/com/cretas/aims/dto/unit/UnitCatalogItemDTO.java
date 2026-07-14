package com.cretas.aims.dto.unit;

import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitDimension;

public record UnitCatalogItemDTO(
        String code,
        String label,
        UnitDimension dimension,
        String baseCode,
        int displayScale) {

    public static UnitCatalogItemDTO from(CanonicalUnit unit) {
        return new UnitCatalogItemDTO(
                unit.code(), unit.displayName(), unit.dimension(), unit.baseCode(), unit.displayScale());
    }
}
