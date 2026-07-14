package com.cretas.aims.dto.unit;

import com.cretas.aims.service.unit.UnitUsageScene;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public record UnitConversionRequest(
        BigDecimal quantity,
        String productTypeId,
        @NotBlank String fromUnit,
        @NotBlank String toUnit,
        LocalDateTime at,
        UnitUsageScene scene,
        Integer scale,
        RoundingMode roundingMode) {
}
