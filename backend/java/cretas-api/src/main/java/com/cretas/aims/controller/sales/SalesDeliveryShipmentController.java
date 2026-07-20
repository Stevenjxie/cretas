package com.cretas.aims.controller.sales;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CreateDeliveryShipmentRequest;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.utils.TokenUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Mother delivery -> child shipment lifecycle. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/sales/deliveries/{parentDeliveryId}/shipments")
@RequireModule("sales_order")
public class SalesDeliveryShipmentController {
    private final SalesService salesService;
    private final MobileService mobileService;

    @GetMapping
    @RequirePermission({"sales:read", "sales:read_write", "warehouse:read", "warehouse:read_write"})
    public ApiResponse<List<SalesDeliveryRecord>> list(
            @PathVariable String factoryId, @PathVariable String parentDeliveryId) {
        return ApiResponse.success("查询成功", salesService.getDeliveryShipments(factoryId, parentDeliveryId));
    }

    @PostMapping
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesDeliveryRecord> create(
            @PathVariable String factoryId,
            @PathVariable String parentDeliveryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateDeliveryShipmentRequest request) {
        Long userId = mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
        return ApiResponse.success("子发运单创建成功",
                salesService.createDeliveryShipment(factoryId, parentDeliveryId, request, userId));
    }

    @PostMapping("/{shipmentId}/cancel")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesDeliveryRecord> cancel(
            @PathVariable String factoryId,
            @PathVariable String parentDeliveryId,
            @PathVariable String shipmentId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
        return ApiResponse.success("子发运单已取消，剩余可安排数量已释放",
                salesService.cancelDeliveryShipment(factoryId, parentDeliveryId, shipmentId, userId));
    }
}
