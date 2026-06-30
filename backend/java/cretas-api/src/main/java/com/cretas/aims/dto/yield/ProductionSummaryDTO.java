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
    /** 成品总重(kg) = Σ 末道(COMPLETED)行 productWeight; 未录时 null */
    private BigDecimal totalFinishedWeight;
    private BigDecimal remainingSemiFinished;
    private BigDecimal realYieldRate;
    /** 成品重量未录入时的提示文字; realYieldRate 为 null 时填充 */
    private String yieldNote;
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
