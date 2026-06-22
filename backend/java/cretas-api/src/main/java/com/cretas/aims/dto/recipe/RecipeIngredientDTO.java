package com.cretas.aims.dto.recipe;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class RecipeIngredientDTO {
    private String id;
    private String section;        // INJECTION | COOKING
    private Integer seq;
    private String name;
    private BigDecimal dosagePerKgG;
    private BigDecimal priceSource1;
    private BigDecimal priceSource2;
    private Boolean countInSeasoning;
    private String remark;
}
