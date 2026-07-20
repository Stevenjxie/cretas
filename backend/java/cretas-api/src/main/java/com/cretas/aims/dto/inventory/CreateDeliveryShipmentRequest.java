package com.cretas.aims.dto.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Create one child shipment under a master sales-delivery arrangement. */
@Data
public class CreateDeliveryShipmentRequest {
    @Size(max = 191)
    private String idempotencyKey;
    @NotNull
    private LocalDate plannedShipmentDate;
    private LocalDate actualShipmentDate;
    @Size(max = 30)
    private String deliveryMethod;
    @Size(max = 100)
    private String logisticsCompany;
    @Size(max = 100)
    private String trackingNumber;
    @Size(max = 500)
    private String deliveryAddress;
    @Size(max = 5000)
    private String remark;
    @Valid
    @NotEmpty
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull
        private Long parentDeliveryItemId;
        @NotNull
        @DecimalMin("0.01")
        private BigDecimal quantity;
    }
}
