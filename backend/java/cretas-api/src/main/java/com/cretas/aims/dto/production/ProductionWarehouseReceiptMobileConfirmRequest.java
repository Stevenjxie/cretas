package com.cretas.aims.dto.production;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductionWarehouseReceiptMobileConfirmRequest {
    @NotNull(message = "receivedQuantity cannot be null")
    @DecimalMin(value = "0.0001", message = "receivedQuantity must be greater than 0")
    private BigDecimal receivedQuantity;

    @Size(max = 1000, message = "note cannot exceed 1000 characters")
    private String note;
}
