package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.Valid;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SeasoningBindingUpdateRequest {
    @NotNull @PositiveOrZero
    private Long expectedRevision;
    @NotBlank
    private String materialTypeId;
    @NotNull @DecimalMin(value = "0", inclusive = false)
    private BigDecimal dosagePerKgG;
    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal subsequentPotRatio;
    private Boolean countInSeasoning;
    private String remark;
    @Valid
    private List<BomSubstituteInput> substitutes;
}
