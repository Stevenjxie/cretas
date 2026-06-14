package com.cretas.aims.dto.bom;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BomPriceAdjustmentCheckRequest {
    private String materialTypeId;
    private String materialName;
    private BigDecimal latestPreTaxPrice;
}
