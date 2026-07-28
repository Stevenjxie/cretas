package com.cretas.aims.dto.production;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProductionWarehouseReceiptMobileConfirmRequest {
    @DecimalMin(value = "0.0001", message = "receivedQuantity must be greater than 0")
    private BigDecimal receivedQuantity;

    @Valid
    private List<ProductionWarehouseReceiptRequest.ReceiptLine> outputLines = new ArrayList<>();

    @Size(max = 1000, message = "note cannot exceed 1000 characters")
    private String note;
}
