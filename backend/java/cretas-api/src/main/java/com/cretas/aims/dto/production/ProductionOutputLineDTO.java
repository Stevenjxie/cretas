package com.cretas.aims.dto.production;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOutputLineDTO {
    private String productTypeId;
    private String reportedBatchNumber;
    private BigDecimal reportedQuantity;
    private String quantityUnit;
    private String bomFamilyId;
    private String bomRecipeId;
    private Integer bomRecipeVersion;
    private String outputRole;
    private BigDecimal costAllocationRatio;
    @PriceSensitive
    private BigDecimal allocatedCost;
    @PriceSensitive
    private BigDecimal unitCost;
    private BigDecimal receivedQuantity;
    private String finishedGoodsBatchId;
    private String status;
}
