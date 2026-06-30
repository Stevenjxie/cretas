package com.cretas.aims.dto.yield;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ProductionSummaryDTO {
    private String planId;
    private String planNumber;
    private String productTypeId;
    private String productName;
    private BigDecimal totalRawInput;
    private BigDecimal totalFinishedOutput;
    private BigDecimal remainingSemiFinished;
    private BigDecimal realYieldRate;
    private BigDecimal totalCost;
    private boolean priceMasked;
    private List<BatchLine> batches;

    @Data
    @Builder
    public static class BatchLine {
        private String batchNumber;
        private Integer processOrder;
        private String processName;
        private BigDecimal produced;
        private BigDecimal remaining;
        private String status;
        private BigDecimal cumulativeYieldRate;
    }
}
