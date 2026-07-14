package com.cretas.aims.service;

import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitDimension;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Backward-compatible facade. All conversion truth is delegated to {@link UnitContractService}.
 */
@Service
public class UnitConversionService {

    private final UnitContractService unitContractService;

    public UnitConversionService(UnitContractService unitContractService) {
        this.unitContractService = unitContractService;
    }

    public BigDecimal convert(BigDecimal value, String fromUnit, String toUnit) {
        if (value == null || fromUnit == null || toUnit == null) return null;
        UnitConversionResult result = unitContractService.convert(value, context(fromUnit, toUnit));
        return result.succeeded() && result.quantity() != null
                ? result.quantity().setScale(6, RoundingMode.HALF_UP)
                : null;
    }

    public BigDecimal toKg(BigDecimal value, String unit) {
        return convert(value, unit, "kg");
    }

    public boolean isWeightUnit(String unit) {
        return unitContractService.describe(null, unit)
                .map(canonical -> canonical.dimension() == UnitDimension.MASS)
                .orElse(false);
    }

    public BigDecimal convertOrSame(BigDecimal value, String fromUnit, String toUnit) {
        BigDecimal converted = convert(value, fromUnit, toUnit);
        return converted != null ? converted : value;
    }

    public boolean isSupported(String fromUnit, String toUnit) {
        if (fromUnit == null || toUnit == null) return false;
        return unitContractService.convert(BigDecimal.ONE, context(fromUnit, toUnit)).succeeded();
    }

    private UnitConversionContext context(String fromUnit, String toUnit) {
        return new UnitConversionContext(
                null, null, fromUnit, toUnit, LocalDateTime.now(), null, 6, RoundingMode.HALF_UP);
    }
}
