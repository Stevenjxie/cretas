package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionWarehouseReceiptMobileDTO {
    private String id;
    private String direction;
    private String status;
    private String sourceNumber;
    private String productName;
    private String batchNumber;
    private BigDecimal plannedQuantity;
    private BigDecimal reportedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal toleranceQuantity;
    private String unit;
    private String fromLocation;
    private String toWarehouseName;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private String note;

    @Builder.Default
    private List<OutputLine> outputLines = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputLine {
        private String productTypeId;
        /**
         * 逐行的真实品名。仓管在「待确认入库」屏上逐行核对时看的是这个,
         * 不是 {@link #productTypeId} 那串 UUID。查不到时是一句说明性的中文,
         * 绝不回落成 UUID / 空串 (见 controller 的 UNKNOWN_PRODUCT_NAME)。
         * productTypeId 保留在载荷里供确认回传和技术支持定位用。
         */
        private String productName;
        private String batchNumber;
        private BigDecimal reportedQuantity;
        private BigDecimal receivedQuantity;
        private String unit;
        private String status;
    }
}
