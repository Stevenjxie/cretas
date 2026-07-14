package com.cretas.aims.service.unit;

public record UnitNormalizationResult(String raw, String code, CanonicalUnit unit) {

    public boolean recognized() {
        return unit != null;
    }
}
