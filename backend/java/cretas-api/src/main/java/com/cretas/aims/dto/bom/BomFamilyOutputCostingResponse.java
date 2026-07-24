package com.cretas.aims.dto.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class BomFamilyOutputCostingResponse {
    private String bomFamilyId;
    private boolean editable;
    private List<OutputCosting> outputs = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputCosting {
        private String recipeId;
        private String productTypeId;
        private String productName;
        private BomRecipe.OutputRole outputRole;
        private BigDecimal costAllocationRatio;
        private BigDecimal outputQuantity;
        private String outputUnit;
        @PriceSensitive
        private BigDecimal byproductNrvUnitPrice;
    }
}
