package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionWarehouseReceiptResponse {
    private String settlementId;
    private String productionPlanId;
    private String planNumber;
    private BigDecimal productionReportedQuantity;
    private BigDecimal warehouseReceivedQuantity;
    private BigDecimal varianceQuantity;
    private BigDecimal toleranceQuantity;
    private String quantityUnit;
    private String postingStatus;
    private String finishedGoodsBatchId;
    private String transitLedgerId;
    private String message;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
