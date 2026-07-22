package com.cretas.aims.controller.warehouse;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical warehouse-owned receiving API. It intentionally reuses the proven
 * purchase receipt service and inventory posting transaction instead of creating
 * a second receipt model. New UI and workflows must use this namespace.
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/warehouse/receiving")
@RequiredArgsConstructor
@Tag(name = "仓储收货", description = "已审批来源单的仓储待收货、收货单与入库确认")
public class WarehouseReceivingController {

    private final PurchaseService purchaseService;
    private final MobileService mobileService;
    private final WarehouseResolver warehouseResolver;
    private final FactoryWarehouseRepository factoryWarehouseRepository;

    @RequireModule("warehouse")
    @GetMapping("/tasks")
    @Operation(summary = "仓储待收货任务")
    @RequirePermission({"warehouse:read_write", "warehouse:read", "inventory:write"})
    public ApiResponse<List<PurchaseReceivingTaskResponse>> getTasks(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String purchaseOrderId,
            @RequestParam(required = false) String orderNumber) {
        return ApiResponse.success("查询成功",
                purchaseService.getPendingReceivingTasks(factoryId, purchaseOrderId, orderNumber));
    }

    @RequireModule("warehouse")
    @GetMapping("/default-warehouse")
    @Operation(summary = "采购收货默认仓库")
    @RequirePermission({"warehouse:read_write", "warehouse:read", "inventory:write"})
    public ApiResponse<FactoryWarehouse> getDefaultWarehouse(
            @PathVariable @NotBlank String factoryId) {
        FactoryWarehouse warehouse = null;
        try {
            String warehouseId = warehouseResolver.resolvePurchaseInboundWh(factoryId);
            warehouse = factoryWarehouseRepository
                    .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                    .orElse(null);
        } catch (BusinessException ignored) {
            // Honest null: caller must choose a valid warehouse; no write is performed.
        }
        return ApiResponse.success("查询成功", warehouse);
    }

    @RequireModule("warehouse")
    @PostMapping("/receipts")
    @Operation(summary = "从已审批来源任务创建收货单")
    @RequirePermission({"warehouse:read_write", "inventory:write"})
    public ApiResponse<PurchaseReceiveRecord> createReceipt(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateReceiveRecordRequest request) {
        if (request.getPurchaseOrderId() == null || request.getPurchaseOrderId().isBlank()) {
            throw new BusinessException(400, "收货单必须关联已审批采购订单")
                    .withCode("PURCHASE_RECEIPT_SOURCE_REQUIRED")
                    .withHint("请从仓储待收货任务进入");
        }
        PurchaseReceiveRecord record = purchaseService.createReceiveRecord(
                factoryId, request, extractUserId(authorization));
        return ApiResponse.success("收货单创建成功", record);
    }

    @RequireModule("warehouse")
    @GetMapping("/receipts/{receiptId}")
    @Operation(summary = "收货单详情")
    @RequirePermission({"warehouse:read_write", "warehouse:read"})
    public ApiResponse<PurchaseReceiveRecord> getReceipt(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String receiptId) {
        return ApiResponse.success("查询成功",
                purchaseService.getReceiveRecordById(factoryId, receiptId));
    }

    @RequireModule("warehouse")
    @GetMapping("/receipts/by-order/{orderId}")
    @Operation(summary = "按采购订单查询收货记录")
    @RequirePermission({"warehouse:read_write", "warehouse:read", "procurement:read"})
    public ApiResponse<List<PurchaseReceiveRecord>> getReceiptsByOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        return ApiResponse.success("查询成功",
                purchaseService.getReceiveRecordsByOrder(factoryId, orderId));
    }

    @RequireModule("warehouse")
    @PostMapping("/receipts/{receiptId}/confirm")
    @Operation(summary = "确认收货并生成库存批次")
    @RequirePermission({"warehouse:read_write", "inventory:write"})
    public ApiResponse<PurchaseReceiveRecord> confirmReceipt(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String receiptId,
            @RequestHeader("Authorization") String authorization) {
        PurchaseReceiveRecord record = purchaseService.confirmReceive(
                factoryId, receiptId, extractUserId(authorization));
        return ApiResponse.success("收货确认成功，库存批次已生成", record);
    }

    private Long extractUserId(String authorization) {
        return mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
    }
}
