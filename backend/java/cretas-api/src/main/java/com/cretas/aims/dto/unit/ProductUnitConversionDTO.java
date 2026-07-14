package com.cretas.aims.dto.unit;

import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.service.unit.UnitDimension;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductUnitConversionDTO(
        String id,
        String productTypeId,
        @NotBlank String fromUnitCode,
        String fromUnitLabel,
        UnitDimension fromDimension,
        @NotBlank String toUnitCode,
        String toUnitLabel,
        UnitDimension toDimension,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factor,
        @NotNull ProductUnitConversion.SourceType sourceType,
        Boolean primarySalesConversion,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        Long version) {
}
