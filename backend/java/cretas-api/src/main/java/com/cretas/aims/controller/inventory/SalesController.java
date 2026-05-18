package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.dto.inventory.SignatureUploadRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.FinanceCostBreakdown;
import com.cretas.aims.dto.inventory.FinanceReviewRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.annotation.RequirePermission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/sales")
@RequiredArgsConstructor
@Tag(name = "销售管理", description = "销售订单、发货出库、成品库存（工厂/餐饮通用）")
public class SalesController {

    private final SalesService salesService;
    private final MobileService mobileService;
    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final SalesOrderRepository salesOrderRepository;

    // ==================== 销售订单 ====================

    @PostMapping("/orders")
    @Operation(summary = "创建销售订单")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesOrder> createOrder(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateSalesOrderRequest request) {
        Long userId = extractUserId(authorization);
        log.info("创建销售订单: factoryId={}, customerId={}", factoryId, request.getCustomerId());
        SalesOrder order = salesService.createSalesOrder(factoryId, request, userId);
        return ApiResponse.success("销售订单创建成功", order);
    }

    @GetMapping("/orders")
    @Operation(summary = "销售订单列表 (Bug G: ?keyword 搜索 — Rule 12.1)")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<PageResponse<SalesOrder>> listOrders(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<SalesOrder> result = salesService.getSalesOrders(factoryId, keyword, page, size);
        return ApiResponse.success("查询成功", result);
    }

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 — tab 7 (销售单 by customer).
     * Paginated, newest first. Repo-backed (bypass service for minimal blast radius).
     */
    @GetMapping("/orders/by-customer")
    @Operation(summary = "客户档案 360° tab 7 销售单 by customer")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<PageResponse<SalesOrder>> listOrdersByCustomer(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        var p = salesOrderRepository.findByFactoryIdAndCustomerIdOrderByCreatedAtDesc(
                factoryId, customerId,
                org.springframework.data.domain.PageRequest.of(safePage - 1, safeSize));
        var result = PageResponse.of(p.getContent(), safePage, safeSize, p.getTotalElements());
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/orders/by-status")
    @Operation(summary = "按状态查询销售订单")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<PageResponse<SalesOrder>> listOrdersByStatus(
            @PathVariable @NotBlank String factoryId,
            @RequestParam SalesOrderStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("Authorization") String authorization) {
        // Issue #736 sister-sweep (2026-05-17): sales orders 也有 PENDING_FINANCE_REVIEW 工作流
        // (SalesController:135 submit-for-review → status). viewer 不应看到该 list.
        if (status == SalesOrderStatus.PENDING_FINANCE_REVIEW) {
            User currentUser = resolveCurrentUser(authorization);
            boolean canViewFinanceReview = currentUser != null
                    && permissionService.hasPermission(currentUser,
                            com.cretas.aims.service.impl.PermissionServiceImpl.FINANCE_REVIEW_VIEW_PERMISSION);
            if (!canViewFinanceReview) {
                log.warn("RBAC reject (#736): user={} role={} 无 finance_review 权限, "
                        + "sales status=PENDING_FINANCE_REVIEW 拒绝访问",
                        currentUser != null ? currentUser.getUsername() : "<null>",
                        currentUser != null ? currentUser.getRole() : "<null>");
                throw new BusinessException(403, "无权查看待财审销售单");
            }
        }
        PageResponse<SalesOrder> result = salesService.getSalesOrdersByStatus(factoryId, status, page, size);
        return ApiResponse.success("查询成功", result);
    }

    private User resolveCurrentUser(String authorization) {
        if (authorization == null) return null;
        try {
            String token = TokenUtils.extractToken(authorization);
            if (token == null) return null;
            Long uid = mobileService.getUserFromToken(token).getId();
            if (uid == null) return null;
            return userRepository.findById(uid).orElse(null);
        } catch (Exception e) {
            log.warn("resolveCurrentUser failed: {}", e.getMessage());
            return null;
        }
    }

    @PutMapping("/orders/{orderId}")
    @Operation(summary = "更新销售订单", description = "仅 DRAFT 状态可编辑")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesOrder> updateOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @Valid @RequestBody UpdateSalesOrderRequest request) {
        log.info("更新销售订单: factoryId={}, orderId={}", factoryId, orderId);
        SalesOrder order = salesService.updateSalesOrder(factoryId, orderId, request);
        return ApiResponse.success("销售订单更新成功", order);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "销售订单详情")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<SalesOrder> getOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        SalesOrder order = salesService.getSalesOrderById(factoryId, orderId);
        return ApiResponse.success("查询成功", order);
    }

    @GetMapping("/orders/{orderId}/formulas")
    @Operation(summary = "计算销售订单公式 (如税率分组求和)")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<Map<String, Object>> getOrderFormulas(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        salesService.getSalesOrderById(factoryId, orderId);
        Map<String, Object> formulas = salesService.computeOrderFormulas(factoryId, orderId);
        return ApiResponse.success("公式计算完成", formulas);
    }

    @PostMapping("/orders/{orderId}/confirm")
    @Operation(summary = "确认销售订单")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesOrder> confirmOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        SalesOrder order = salesService.confirmOrder(factoryId, orderId);
        return ApiResponse.success("销售订单已确认", order);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "取消销售订单")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesOrder> cancelOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        SalesOrder order = salesService.cancelOrder(factoryId, orderId);
        return ApiResponse.success("销售订单已取消", order);
    }

    /**
     * 复制销售订单 — #860 follow-up.
     * 基于现有订单创建新草稿, 复制客户/品项/价格, 不复制审批/发货/收款状态.
     */
    @PostMapping("/orders/{orderId}/copy")
    @Operation(summary = "复制销售订单 (创建新草稿)")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesOrder> copyOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        log.info("复制销售订单: factoryId={}, sourceOrderId={}", factoryId, orderId);
        SalesOrder order = salesService.copySalesOrder(factoryId, orderId, userId);
        return ApiResponse.success("销售订单已复制为新草稿", order);
    }

    // ==================== 财务审核 ====================

    @PostMapping("/orders/{orderId}/submit-for-review")
    @Operation(summary = "提交财务审核", description = "CONFIRMED -> PENDING_FINANCE_REVIEW")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesOrder> submitForFinanceReview(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        SalesOrder order = salesService.submitForFinanceReview(factoryId, orderId);
        return ApiResponse.success("销售订单已提交财务审核", order);
    }

    @PostMapping("/orders/{orderId}/finance-approve")
    @Operation(summary = "财务审核通过", description = "PENDING_FINANCE_REVIEW -> FINANCE_APPROVED, 触发供应链联动")
    @RequirePermission({"finance:read_write", "sales:read_write"})
    public ApiResponse<SalesOrder> financeApproveOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader("Authorization") String authorization,
            // R38 BUG-4 fix: DynamicModulePage transitions 不发 body, 改 required=false 接受空 POST
            @RequestBody(required = false) FinanceReviewRequest request) {
        Long reviewerId = extractUserId(authorization);
        String notes = request != null ? request.getNotes() : null;
        java.math.BigDecimal estimatedCost = request != null ? request.getEstimatedCost() : null;
        SalesOrder order = salesService.financeApproveOrder(
                factoryId, orderId, notes, estimatedCost, reviewerId);
        return ApiResponse.success("销售订单财务审核通过", order);
    }

    @PostMapping("/orders/{orderId}/finance-reject")
    @Operation(summary = "财务审核驳回", description = "PENDING_FINANCE_REVIEW -> FINANCE_REJECTED")
    @RequirePermission({"finance:read_write", "sales:read_write"})
    public ApiResponse<SalesOrder> financeRejectOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader("Authorization") String authorization,
            // R38 BUG-4 fix: DynamicModulePage transitions 不发 body
            @RequestBody(required = false) FinanceReviewRequest request) {
        Long reviewerId = extractUserId(authorization);
        String notes = request != null ? request.getNotes() : null;
        // #818 fix (2026-05-17): mirror PR #704 Purchase fix — 驳回必须填写原因,
        // 拒绝 null / 空字符串 / 纯空白 notes.
        if (notes == null || notes.trim().isEmpty()) {
            throw new BusinessException(400, "驳回必须填写原因 (notes 不能为空)");
        }
        SalesOrder order = salesService.financeRejectOrder(factoryId, orderId, notes, reviewerId);
        return ApiResponse.success("销售订单财务审核已驳回", order);
    }

    @GetMapping("/orders/{orderId}/cost-breakdown")
    @Operation(summary = "财务成本核算",
            description = "Sprint4-H F-AR-1: BOM 标准成本 + 预估成本 + 实际成本 + 利润对比")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write"})
    public ApiResponse<FinanceCostBreakdown> getOrderCostBreakdown(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        FinanceCostBreakdown breakdown = salesService.getOrderCostBreakdown(factoryId, orderId);
        return ApiResponse.success("查询成功", breakdown);
    }

    // ==================== 发货/出库 ====================

    @PostMapping("/deliveries")
    @Operation(summary = "创建发货单")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesDeliveryRecord> createDelivery(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateDeliveryRequest request) {
        Long userId = extractUserId(authorization);
        log.info("创建发货单: factoryId={}, customerId={}", factoryId, request.getCustomerId());
        SalesDeliveryRecord record = salesService.createDeliveryRecord(factoryId, request, userId);
        return ApiResponse.success("发货单创建成功", record);
    }

    @GetMapping("/deliveries")
    @Operation(summary = "发货单列表")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<PageResponse<SalesDeliveryRecord>> listDeliveries(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<SalesDeliveryRecord> result = salesService.getDeliveryRecords(factoryId, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/deliveries/{deliveryId}")
    @Operation(summary = "发货单详情")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<SalesDeliveryRecord> getDelivery(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String deliveryId) {
        SalesDeliveryRecord record = salesService.getDeliveryRecordById(factoryId, deliveryId);
        return ApiResponse.success("查询成功", record);
    }

    @PostMapping("/deliveries/{deliveryId}/ship")
    @Operation(summary = "发货确认（扣减成品库存）")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesDeliveryRecord> shipDelivery(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String deliveryId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        SalesDeliveryRecord record = salesService.shipDelivery(factoryId, deliveryId, userId);
        return ApiResponse.success("发货成功，成品库存已扣减", record);
    }

    @PostMapping("/deliveries/{deliveryId}/delivered")
    @Operation(summary = "签收确认")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesDeliveryRecord> confirmDelivered(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String deliveryId) {
        SalesDeliveryRecord record = salesService.confirmDelivered(factoryId, deliveryId);
        return ApiResponse.success("签收确认成功", record);
    }

    @PostMapping("/deliveries/{deliveryId}/signature")
    @Operation(summary = "上传签收凭证 (P0-NEW-1 — 客户原话 2807s)")
    @RequirePermission("sales:read_write")
    public ApiResponse<SalesDeliveryRecord> uploadSignature(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String deliveryId,
            @Valid @RequestBody SignatureUploadRequest request) {
        log.info("上传签收凭证: factoryId={}, deliveryId={}, 照片数={}",
                factoryId, deliveryId,
                request.getPhotoUrls() == null ? 0 : request.getPhotoUrls().size());
        SalesDeliveryRecord record = salesService.uploadDeliverySignature(
                factoryId, deliveryId,
                request.getPhotoUrls(), request.getSignedByName(), request.getRemark());
        return ApiResponse.success("签收凭证已保存", record);
    }

    @GetMapping("/deliveries/by-order/{orderId}")
    @Operation(summary = "按销售订单查询发货记录")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<List<SalesDeliveryRecord>> getDeliveriesByOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        List<SalesDeliveryRecord> records = salesService.getDeliveryRecordsByOrder(orderId);
        return ApiResponse.success("查询成功", records);
    }

    // ==================== 成品库存 ====================

    @GetMapping("/finished-goods")
    @Operation(summary = "成品库存列表")
    @RequirePermission({"sales:read_write", "sales:read", "inventory:read"})
    public ApiResponse<PageResponse<FinishedGoodsBatch>> listFinishedGoods(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<FinishedGoodsBatch> result = salesService.getFinishedGoodsBatches(factoryId, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/finished-goods/available")
    @Operation(summary = "查询可用成品批次（按产品 + 可选 sourceWarehouseCode）")
    @RequirePermission({"sales:read_write", "sales:read", "inventory:read"})
    public ApiResponse<List<FinishedGoodsBatch>> getAvailableBatches(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String productTypeId,
            @RequestParam(required = false) String sourceWarehouseCode) {
        // T4-D5 #572 Phase B-1: sourceWarehouseCode 可选; null/空时回落 WH-LOG (D5 默认).
        List<FinishedGoodsBatch> batches = salesService.getAvailableBatches(
                factoryId, productTypeId, sourceWarehouseCode);
        return ApiResponse.success("查询成功", batches);
    }

    /**
     * Issue #786 follow-up to PR #782 / #761: single-item lookup for finished-goods detail page.
     *
     * <p>detail.vue 之前走 list-filter fallback (TODO comment) — 跨 page 拿不到 + 大 list
     * 性能差. 现在 detail.vue 改用 fetch-by-ID 直接走此 endpoint.
     *
     * <p>NOTE: 注意 path order — Spring 匹配 {batchId} 之前已经匹配 /available (上方更具体).
     */
    @GetMapping("/finished-goods/{batchId}")
    @Operation(summary = "成品批次详情",
            description = "Issue #786 — 替代前端 list-filter fallback. 跨 page 也能直接 fetch by ID.")
    @RequirePermission({"sales:read_write", "sales:read", "inventory:read"})
    public ApiResponse<FinishedGoodsBatch> getFinishedGoodsBatch(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String batchId) {
        FinishedGoodsBatch batch = salesService.getFinishedGoodsBatchById(factoryId, batchId);
        return ApiResponse.success("查询成功", batch);
    }

    // ==================== 统计 ====================

    @GetMapping("/statistics")
    @Operation(summary = "销售统计数据")
    @RequirePermission({"sales:read_write", "sales:read", "report:read"})
    public ApiResponse<Map<String, Object>> getStatistics(
            @PathVariable @NotBlank String factoryId) {
        Map<String, Object> stats = salesService.getSalesStatistics(factoryId);
        return ApiResponse.success("查询成功", stats);
    }

    // ==================== 内部方法 ====================

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}
