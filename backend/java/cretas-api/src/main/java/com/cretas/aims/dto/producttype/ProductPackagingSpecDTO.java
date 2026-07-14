package com.cretas.aims.dto.producttype;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductPackagingSpecDTO(
        String id,
        String name,
        @NotBlank String packageUnit,
        @NotBlank String baseUnit,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal conversionFactor,
        Boolean defaultSpec,
        Boolean active,
        Integer sortOrder,
        Long version) {
}
