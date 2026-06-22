package com.cretas.aims.dto.recipe;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductRecipeDTO {
    private String id;
    private String factoryId;
    private String productTypeId;
    private String name;
    private BigDecimal injectionRate;
    private BigDecimal cookingPotBaseKg;
    private BigDecimal subsequentPotRatio;
    private String status;
    private Integer version;
    private List<RecipeIngredientDTO> ingredients;

    // 算出的展示值(每kg原料)
    private BigDecimal injectionCostPerKg;
    private BigDecimal cookingFullCostPerKg;
    private BigDecimal costPerKgFirstPot;      // 注射 + 熟制全量
    private BigDecimal costPerKgSubsequentPot; // 注射 + 熟制×ratio
}
