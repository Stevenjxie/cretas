package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Injection-only process configuration. Cooking pot ratios live on seasoning bindings. */
@Data
public class ProcessInjectionConfigDTO {

    @NotBlank(message = "workProcessId 不可为空")
    private String workProcessId;

    @NotNull(message = "injectionAmountKg 不可为空")
    @DecimalMin(value = "0.001", message = "injectionAmountKg 必须大于 0")
    private BigDecimal injectionAmountKg;

    @Size(max = 500, message = "notes 最多 500 字")
    private String notes;
}
