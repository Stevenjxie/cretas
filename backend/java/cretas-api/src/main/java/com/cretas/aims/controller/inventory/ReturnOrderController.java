package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.annotation.RequirePermission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/return-orders")
@RequiredArgsConstructor
@Tag(name = "退货管理", description = "采购退货与销售退货管理")
@RequirePermission({"sales:read_write", "procurement:read_write"})
public class ReturnOrderController {

    private final ReturnOrderService returnOrderService;
    private final MobileService mobileService;
    private final com.cretas.aims.repository.inventory.ReturnOrderRepository returnOrderRepository;

    @PostMapping
    @Operation(summary = "创建退货单")
    public ApiResponse<ReturnOrder> createReturnOrder(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateReturnOrderRequest request) {
        Long userId = extractUserId(authorization);
        log.info("创建退货单: factoryId={}, type={}", factoryId, request.getReturnType());
        ReturnOrder order = returnOrderService.createReturnOrder(factoryId, request, userId);
        return ApiResponse.success("退货单创建成功", order);
    }

    @GetMapping
    @Operation(summary = "退货单列表")
    @RequirePermission({"sales:read_write", "sales:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<PageResponse<ReturnOrder>> listReturnOrders(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) ReturnType returnType,
            @RequestParam(required = false) ReturnOrderStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ReturnOrder> result = returnOrderService.getReturnOrders(factoryId, returnType, status, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/{returnOrderId}")
    @Operation(summary = "退货单详情")
    @RequirePermission({"sales:read_write", "sales:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<ReturnOrder> getReturnOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String returnOrderId) {
        ReturnOrder order = returnOrderService.getReturnOrderById(factoryId, returnOrderId);
        return ApiResponse.success("查询成功", order);
    }

    @PostMapping("/{returnOrderId}/submit")
    @Operation(summary = "提交退货单")
    public ApiResponse<ReturnOrder> submitReturnOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String returnOrderId) {
        ReturnOrder order = returnOrderService.submitReturnOrder(factoryId, returnOrderId);
        return ApiResponse.success("退货单已提交", order);
    }

    @PostMapping("/{returnOrderId}/approve")
    @Operation(summary = "审批退货单")
    public ApiResponse<ReturnOrder> approveReturnOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String returnOrderId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        ReturnOrder order = returnOrderService.approveReturnOrder(factoryId, returnOrderId, userId);
        return ApiResponse.success("退货单已审批", order);
    }

    @PostMapping("/{returnOrderId}/reject")
    @Operation(summary = "驳回退货单")
    public ApiResponse<ReturnOrder> rejectReturnOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String returnOrderId) {
        ReturnOrder order = returnOrderService.rejectReturnOrder(factoryId, returnOrderId);
        return ApiResponse.success("退货单已驳回", order);
    }

    @PostMapping("/{returnOrderId}/complete")
    @Operation(summary = "完成退货单")
    public ApiResponse<ReturnOrder> completeReturnOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String returnOrderId) {
        ReturnOrder order = returnOrderService.completeReturnOrder(factoryId, returnOrderId);
        return ApiResponse.success("退货单已完成", order);
    }

    @GetMapping("/statistics")
    @Operation(summary = "退货统计数据")
    @RequirePermission({"sales:read_write", "sales:read", "procurement:read_write", "procurement:read", "report:read"})
    public ApiResponse<Map<String, Object>> getStatistics(
            @PathVariable @NotBlank String factoryId) {
        Map<String, Object> stats = returnOrderService.getReturnOrderStatistics(factoryId);
        return ApiResponse.success("查询成功", stats);
    }

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 — tab 17 (退货 by customer).
     * 通过 counterpartyId + returnType=SALES_RETURN 过滤客户的销售退货.
     * (ReturnOrder 没有 customerId 字段, 用 counterpartyId+returnType 组合标识销售退货.)
     */
    @GetMapping("/by-customer")
    @Operation(summary = "客户档案 360° tab 17 销售退货 by customer")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<PageResponse<ReturnOrder>> listReturnsByCustomer(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        var p = returnOrderRepository.findByFactoryIdAndCounterpartyIdAndReturnTypeOrderByCreatedAtDesc(
                factoryId, customerId, ReturnType.SALES_RETURN,
                org.springframework.data.domain.PageRequest.of(safePage - 1, safeSize));
        var result = PageResponse.of(p.getContent(), safePage, safeSize, p.getTotalElements());
        return ApiResponse.success("查询成功", result);
    }

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}
