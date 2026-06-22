package com.cretas.aims.dto.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class SaveRecipeRequest {
    @NotBlank
    private String productTypeId;
    @NotBlank
    private String name;
    private BigDecimal injectionRate;
    private BigDecimal cookingPotBaseKg;
    /** 默认 0.3333; service 兜底 */
    private BigDecimal subsequentPotRatio;
    @NotNull
    private List<RecipeIngredientDTO> ingredients;
}
