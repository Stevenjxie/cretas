package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
}
