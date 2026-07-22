package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Read-only warehouse projection of one customer-supplied material requirement. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSuppliedMaterialReceivingTaskResponse {

    public static final String SOURCE = "SALES_ORDER_CUSTOMER_SUPPLIED";

    private String taskId;
    private String sourceType;
    private SalesOrderSuppliedMaterialRequirementStatus status;

    private String factoryId;
    private String customerId;
    private String customerName;
    private String salesOrderId;
    private String salesOrderNumber;
    private SalesOrderStatus salesOrderStatus;

    private Long salesOrderItemId;
    private String salesOrderItemProductTypeId;
    private String salesOrderItemProductName;

    private String materialTypeId;
    private String materialName;
    private BigDecimal expectedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal remainingQuantity;
    private String unit;
    private LocalDateTime expectedArrivalAt;

    private String targetWarehouseId;
    private String targetWarehouseCode;
    private String targetWarehouseName;
}
