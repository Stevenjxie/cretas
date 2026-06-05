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
