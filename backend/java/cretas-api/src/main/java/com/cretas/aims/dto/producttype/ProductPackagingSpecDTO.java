package com.cretas.aims.dto.producttype;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductPackagingSpecDTO(
        String id,
        String name,
        @NotBlank String packageUnit,
        @NotBlank String baseUnit,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 0) BigDecimal conversionFactor,
        Boolean defaultSpec,
        Boolean active,
        Integer sortOrder,
        Long version) {
}
