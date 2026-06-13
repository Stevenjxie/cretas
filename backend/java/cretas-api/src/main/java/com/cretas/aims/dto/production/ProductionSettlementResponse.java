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
public class ProductionSettlementResponse {
    private String settlementId;
    private String productionPlanId;
    private String planNumber;
    private String status;
    private BigDecimal plannedQuantity;
    private BigDecimal actualFinishedQuantity;
    private BigDecimal actualSemiFinishedQuantity;
    private String postingStatus;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> createdClearingLedgerIds = new ArrayList<>();

    @Builder.Default
    private List<String> createdInventoryTxnIds = new ArrayList<>();
}
