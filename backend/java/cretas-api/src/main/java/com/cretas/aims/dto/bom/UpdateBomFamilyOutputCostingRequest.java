package com.cretas.aims.dto.bom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdateBomFamilyOutputCostingRequest {
    @NotEmpty
    @Valid
    private List<OutputCostingInput> outputs;

    @Data
    public static class OutputCostingInput {
        @NotBlank
        private String recipeId;

        /** Required only for BY_PRODUCT; rejected for MAIN and CO_PRODUCT. */
        @Positive
        private BigDecimal byproductNrvUnitPrice;
    }
}
