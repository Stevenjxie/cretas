package com.cretas.aims.dto.production;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
public class ProductionSettlementRequest {

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    /**
     * Normal settlement contract: the caller only confirms that the plan should be closed.
     * When true, the service ignores client-supplied production facts and re-derives them
     * from submitted process reports inside the settlement transaction.
     */
    @Builder.Default
    private boolean confirm = false;

    @PositiveOrZero(message = "actualFinishedQuantity must be >= 0")
    private BigDecimal actualFinishedQuantity;

    @Builder.Default
    @PositiveOrZero(message = "actualSemiFinishedQuantity must be >= 0")
    private BigDecimal actualSemiFinishedQuantity = BigDecimal.ZERO;

    private String quantityUnit;
    private String quantityVarianceReason;
    private String quantityVarianceNote;
    private String materialVarianceReason;
    private String materialVarianceNote;
    private String laborDeferredReason;

    @Valid
    @Builder.Default
    private List<ConsumptionLine> rawMaterialConsumptions = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<ConsumptionLine> semiFinishedConsumptions = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<ConsumptionLine> auxiliaryConsumptions = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<LaborSegment> laborSegments = new ArrayList<>();

    /** Server-derived terminal outputs, keyed by SKU + batch + unit. */
    @Builder.Default
    private List<OutputLine> terminalOutputs = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputLine {
        private String productTypeId;
        private String batchNumber;
        private BigDecimal quantity;
        private String unit;
        /** Exact Workflow material node that produced this terminal batch. */
        private String materialNodeId;
        /** Exact Workflow output port used for the process report. */
        private String workflowPortId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumptionLine {
        private String materialBatchId;
        private Long semiFinishedInventoryId;
        /**
         * Legacy row-level product context. Workflow plans validate the exact
         * material-node/input-port against the pinned BOM family instead;
         * intermediate WIP identity must never be treated as a terminal owner.
         */
        private String productTypeId;
        private String materialTypeId;
        private String batchNumber;
        /** Exact raw-material Cell from the submitted pinned Workflow row. */
        private String workflowMaterialNodeId;
        /** Exact consuming-process input port from the submitted pinned Workflow row. */
        private String workflowInputPortId;

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be > 0")
        private BigDecimal quantity;

        private String unit;
        private String warehouseId;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaborSegment {
        private Long workerId;
        private String workerName;
        private String workType;

        @NotNull(message = "minutes is required")
        @Positive(message = "minutes must be > 0")
        private Integer minutes;

        @Builder.Default
        @Positive(message = "headcount must be > 0")
        private Integer headcount = 1;

        @PositiveOrZero(message = "hourlyRate must be >= 0")
        private BigDecimal hourlyRate;

        @PositiveOrZero(message = "laborCost must be >= 0")
        private BigDecimal laborCost;

        private String note;
    }
}
