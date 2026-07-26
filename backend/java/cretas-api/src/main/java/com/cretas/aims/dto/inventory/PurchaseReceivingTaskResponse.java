package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.cretas.aims.dto.material.MaterialPackagingSpecDTO;

/**
 * 仓储统一入库页的采购待收货只读投影。
 *
 * <p>它不是另一张“任务表”。任务身份稳定等于采购订单 ID，来源于已经完成审批且尚未收齐的
 * 采购订单；因此历史订单无需桥接即可进入待办池，也不会因为重复打开页面产生业务写入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseReceivingTaskResponse {

    private String taskId;
    private String sourceType;
    private String sourceId;
    private String sourceNumber;
    private String counterpartyType;
    private String counterpartyId;
    private String counterpartyName;
    private String purchaseOrderId;
    private String orderNumber;
    private String supplierId;
    private String supplierName;
    private String salesOrderId;
    private String salesOrderNo;
    private String customerId;
    private String customerName;
    private LocalDate expectedDeliveryDate;
    private LocalDateTime expectedArrivalAt;
    private String status;
    private String statusLabel;
    private String warehouseId;
    private String warehouseName;
    private String responsibleName;
    private String activeReceiptId;
    private String activeReceiptNumber;
    private int activeReceiptCount;
    private boolean receiptConflict;

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long purchaseOrderItemId;
        private Long salesOrderItemId;
        private String materialTypeId;
        private String materialName;
        private BigDecimal orderedQuantity;
        private BigDecimal receivedQuantity;
        private BigDecimal activeDraftAllocatedQuantity;
        private BigDecimal remainingReceivableQuantity;
        private String unit;
        private String specification;
        private String materialPackagingSpecId;
        private String inventoryBaseUnit;
        private BigDecimal packageToBaseFactor;
        @Builder.Default
        private List<MaterialPackagingSpecDTO> packagingSpecs = new ArrayList<>();
    }
}
