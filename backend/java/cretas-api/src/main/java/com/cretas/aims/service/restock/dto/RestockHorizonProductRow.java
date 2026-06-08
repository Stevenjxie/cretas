package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class RestockHorizonProductRow {
    private String productTypeId;
    private String productName;
    private String unit;
    private BigDecimal totalDemandQty;
    private BigDecimal fgAvailableQty;
    /** WH-LOG 物流仓现货（盒）——仅此仓成品可直接发货。车间仓（WH-WKS）成品需先调拨到此仓。T134. */
    private BigDecimal fgShippableQty;
    private BigDecimal wipAvailableQty;
    private BigDecimal wipEstimatedQty;
    private BigDecimal scheduledQty;
    private BigDecimal rawAvailableQty;
    private String rawUnit;
    private String rawMaterialName;
    private BigDecimal rawEstimatedFgQty;
    private BigDecimal rawToWipYield;
    private BigDecimal wipToFgYield;
    private BigDecimal rawToFgYield;
    private BigDecimal startingCoverQty;
    private BigDecimal endingAvailableQty;
    private BigDecimal endingShortfallQty;
    private String conversionWarning;
    private List<RestockHorizonDayCell> days;
}
