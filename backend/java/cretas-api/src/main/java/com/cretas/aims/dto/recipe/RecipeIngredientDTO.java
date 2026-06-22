package com.cretas.aims.dto.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RecipeIngredientDTO {
    private String id;
    @NotBlank
    private String section;        // INJECTION | COOKING
    private Integer seq;
    private String name;
    @NotNull
    private BigDecimal dosagePerKgG;
    private BigDecimal priceSource1;
    private BigDecimal priceSource2;
    private Boolean countInSeasoning;
    private String remark;
}
