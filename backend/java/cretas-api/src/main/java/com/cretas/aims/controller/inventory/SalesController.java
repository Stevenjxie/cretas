package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.dto.inventory.EditFinishedGoodsRequest;
import com.cretas.aims.dto.inventory.SignatureUploadRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.FinanceCostBreakdown;
import com.cretas.aims.dto.inventory.FinanceReviewRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.dto.sales.SalesPriceAdjustmentRecordDTO;
import com.cretas.aims.dto.sales.SalesOrderLinkCountsDTO;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
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
import com.cretas.aims.dto.pricing.GrossMarginCheckResult;
import com.cretas.aims.dto.pricing.EstimatePriceCheckResult;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final BusinessLinkRepository businessLinkRepository;
    // T126 Phase 1: validation dependencies for opening + new CRUD endpoints
    private final ProductTypeRepository productTypeRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    // SP5: 毛利红线校验
    private final GrossMarginRedlineService grossMarginRedlineService;
    // P1 #33: 销售价 ≥ 研发预估价 跨流校验
    private final EstimatePriceCheckService estimatePriceCheckService;
    // 改价留痕 + 审批
    private final SalesPriceAdjustmentService priceAdjustmentService;

    /** Sprint 5 Track C-2 — owner_type / target_type literal pinned to SalesOrder. */
    private static final String SO_ENTITY_TYPE = "SALES_ORDER";

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

    @PostMapping("/finished-goods/opening")
    @Operation(summary = "E2E/期初成品入库")
    @RequirePermission("sales:read_write")
    public ApiResponse<FinishedGoodsBatch> createOpeningFinishedGoods(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody OpeningFinishedGoodsRequest request) {
        Long userId = extractUserId(authorization);

        // F3: duplicate batch number validation
        finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, request.batchNumber())
                .ifPresent(existing -> {
                    throw new BusinessException(409, "批次号 " + request.batchNumber() + " 已存在")
                            .withHint("已有批次 " + existing.getId() + ", 请修改批次号或查看");
                });

        // F9: productTypeId existence validation
        productTypeRepository.findByIdAndFactoryId(request.productTypeId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "产品类型不存在或不属于该工厂")
                        .withHint("productTypeId=" + request.productTypeId() + " 在工厂 " + factoryId + " 中未找到"));

        // 气调货入库防呆 (六扇门 "一托36放37, 对方划单"):
        // 标称数(nominalQuantity)与实收数(producedQuantity)不一致时, 必须对方划单确认,
        // 否则拒绝 — 防止仓管员凭印象记数 (fool-proof Rule 1 边界前置 + Rule 2 上下文).
        boolean hasDiscrepancy = request.nominalQuantity() != null
                && request.nominalQuantity().compareTo(request.producedQuantity()) != 0;
        if (hasDiscrepancy && !Boolean.TRUE.equals(request.counterpartyConfirmed())) {
            BigDecimal diff = request.producedQuantity().subtract(request.nominalQuantity());
            throw new BusinessException(422,
                    "气调货标称数 " + request.nominalQuantity().stripTrailingZeros().toPlainString()
                            + " 与实收数 " + request.producedQuantity().stripTrailingZeros().toPlainString()
                            + " 不一致 (差 " + diff.stripTrailingZeros().toPlainString() + "), 需对方划单确认后入库")
                    .withHint("请勾选「对方划单已确认」(counterpartyConfirmed=true) 再提交; 库存按实收数入库");
        }

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setProductTypeId(request.productTypeId());
        batch.setBatchNumber(request.batchNumber());
        // 库存按实收数(producedQuantity)计量 — 气调货入库以实收为准 (一托实收37)
        batch.setProducedQuantity(request.producedQuantity());
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit(request.unit());
        batch.setProductionDate(request.productionDate());
        batch.setRemark(request.remark());
        // 气调货标称/划单留痕 (null 时为普通入库, 不影响现有路径)
        batch.setNominalQuantity(request.nominalQuantity());
        batch.setCounterpartyConfirmed(request.counterpartyConfirmed());
        batch.setInboundRemark(request.inboundRemark());
        // F8: sourceType = "OPENING" so event listeners can filter bulk opening entries
        batch.setCreatedBy(userId);

        // Delegate to service (which publishes FinishedGoodsCreatedEvent with sourceType)
        FinishedGoodsBatch created = salesService.createOpeningFinishedGoodsBatch(factoryId, batch, userId);
        return ApiResponse.success("期初成品入库成功", created);
    }

    // ==================== T126 Phase 1: 成品库存 Web 闭环 ====================

    /**
     * PUT /finished-goods/{batchId} — 编辑成品批次元数据（备注/库位/过期日/成本单价）.
     * <p>⛔ producedQuantity 和 unit 不可通过此接口修改。
     */
    @PutMapping("/finished-goods/{batchId}")
    @Operation(summary = "编辑成品批次元数据 (T126 P1)",
            description = "仅允许修改 remark/storageLocation/expireDate/unitPrice。" +
                    "producedQuantity 通过 /adjust 修改；unit 不可变。")
    @RequirePermission("sales:read_write")
    public ApiResponse<FinishedGoodsBatch> editFinishedGoods(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String batchId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody EditFinishedGoodsRequest request,
            HttpServletRequest httpRequest) {
        // F1: get userId from request attribute (NOT SecurityContextHolder — it's always empty here)
        Long userId = (Long) httpRequest.getAttribute("userId");
        FinishedGoodsBatch updated = salesService.editFinishedGoodsBatch(factoryId, batchId, request, userId);
        return ApiResponse.success("编辑成功", updated);
    }

    /**
     * POST /finished-goods/{batchId}/adjust — 调整成品库存数量.
     * <p>adjustmentQuantity 可正可负；调整后可用量不得为负。
     */
    @PostMapping("/finished-goods/{batchId}/adjust")
    @Operation(summary = "调整成品库存数量 (T126 P1)",
            description = "adjustmentQuantity 正数=增加，负数=减少。调整后可用量不得为负（422）。" +
                    "每次调整写一行 finished_goods_adjustment_log。")
    @RequirePermission("sales:read_write")
    public ApiResponse<FinishedGoodsBatch> adjustFinishedGoods(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String batchId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody AdjustFinishedGoodsRequest request,
            HttpServletRequest httpRequest) {
        // F1: get userId from request attribute (NOT SecurityContextHolder)
        Long userId = (Long) httpRequest.getAttribute("userId");
        FinishedGoodsBatch updated = salesService.adjustFinishedGoodsQuantity(factoryId, batchId, request, userId);
        return ApiResponse.success("调整成功", updated);
    }

    /**
     * POST /finished-goods/{batchId}/void — 软删除成品批次.
     * <p>前提：shippedQuantity == 0 AND reservedQuantity == 0，否则 409。
     */
    @PostMapping("/finished-goods/{batchId}/void")
    @Operation(summary = "作废成品批次 (T126 P1)",
            description = "软删除（设 deleted_at）。前提：无已出库/预留记录，否则 409。" +
                    "禁止物理删除。")
    @RequirePermission("sales:read_write")
    public ApiResponse<Void> voidFinishedGoods(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String batchId,
            @RequestHeader("Authorization") String authorization,
            HttpServletRequest httpRequest) {
        // F1: get userId from request attribute (NOT SecurityContextHolder)
        Long userId = (Long) httpRequest.getAttribute("userId");
        salesService.voidFinishedGoodsBatch(factoryId, batchId, userId);
        return ApiResponse.successMessage("批次已作废");
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

    /**
     * Sprint 5 Track C-2 (G12-1 inline link counter): batch-fetch BusinessLink
     * counts for a list of SO ids. Frontend calls this after rendering the
     * order list to draw the inline {@code 链:N} chip without N+1 queries.
     *
     * <p>Source: Round 12 §B.6 X2 — HJ 销售单 list 行内 link counter.
     * Sprint 5 plan §C item C-2 (P1 4d → MVP).
     *
     * <p>POST chosen over GET because the {@code orderIds} list can exceed
     * URL-length safe limits (200+ rows × 36-char UUIDs).
     *
     * @param orderIds list of SalesOrder.id values (size capped at 500 to
     *                 bound a single SQL IN-clause; caller paginates if more).
     * @return one DTO per id requested; absent counts default to zero.
     */
    @PostMapping("/orders/link-counts")
    @Operation(summary = "批量查询销售单 BusinessLink 计数 (inline link counter)")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<List<SalesOrderLinkCountsDTO>> getOrderLinkCounts(
            @PathVariable @NotBlank String factoryId,
            @RequestBody @Valid OrderLinkCountsRequest request) {
        java.util.List<String> ids = request.getOrderIds() == null
                ? java.util.Collections.emptyList()
                : request.getOrderIds();
        if (ids.isEmpty()) {
            return ApiResponse.success("查询成功", java.util.Collections.emptyList());
        }
        if (ids.size() > 500) {
            throw new BusinessException(400, "orderIds size > 500, 请分页查询");
        }

        // Batch query — one round-trip per direction, no N+1.
        java.util.Map<String, Long> outbound = new java.util.HashMap<>();
        for (Object[] row : businessLinkRepository.countOutboundByOwners(factoryId, SO_ENTITY_TYPE, ids)) {
            outbound.put((String) row[0], ((Number) row[1]).longValue());
        }
        java.util.Map<String, Long> inbound = new java.util.HashMap<>();
        for (Object[] row : businessLinkRepository.countInboundByTargets(factoryId, SO_ENTITY_TYPE, ids)) {
            inbound.put((String) row[0], ((Number) row[1]).longValue());
        }

        java.util.List<SalesOrderLinkCountsDTO> result = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(SalesOrderLinkCountsDTO.builder()
                    .orderId(id)
                    .outboundLinkCount(outbound.getOrDefault(id, 0L))
                    .inboundLinkCount(inbound.getOrDefault(id, 0L))
                    .build());
        }
        return ApiResponse.success("查询成功", result);
    }

    /** Request body for {@link #getOrderLinkCounts}. */
    @lombok.Data
    public static class OrderLinkCountsRequest {
        @jakarta.validation.constraints.NotNull
        private java.util.List<String> orderIds;
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
     * T129: 删除草稿销售订单 (软删除).
     * 仅 DRAFT 状态可删；其他状态返回 409。
     * factoryId 隔离 + sales:read_write 权限校验。
     */
    @DeleteMapping("/orders/{orderId}")
    @Operation(summary = "删除草稿销售订单 (软删除, 仅 DRAFT)")
    @RequirePermission("sales:read_write")
    public ApiResponse<Void> deleteDraftOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        log.info("删除销售订单草稿: factoryId={}, orderId={}", factoryId, orderId);
        salesService.deleteDraft(factoryId, orderId, userId);
        return ApiResponse.success("删除成功", null);
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

    @GetMapping("/orders/{orderId}/multi-stage-cost")
    @Operation(summary = "六扇门多段生产成本逐段分配",
            description = "回溯成品←半成品←原料链, 逐段拆 料/人工/制费 + 半成品 unitCost 逐段累积 + 每盒贡献; "
                    + "契合两点报工 (无逐道工序数据), 人工登下一期时诚实 null")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write"})
    public ApiResponse<com.cretas.aims.dto.inventory.MultiStageCostBreakdown> getOrderMultiStageCost(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        com.cretas.aims.dto.inventory.MultiStageCostBreakdown breakdown =
                salesService.getOrderMultiStageCost(factoryId, orderId);
        return ApiResponse.success("查询成功", breakdown);
    }

    /**
     * B3 售价趋势 — 财审辅助决策.
     *
     * <p>返回该产品最近 N 笔成交销售订单的行级单价，
     * 供财务审核人员快速判断本单售价是否偏离历史水平。
     *
     * @param productTypeId 产品类型 ID (query param)
     * @param limit         最多返回条数，默认 10，最大 20
     */
    @GetMapping("/orders/price-trend")
    @Operation(summary = "产品售价趋势 (B3 财审辅助)",
            description = "返回该产品最近 N 笔成交单价，供财务审核对比判断本单价格是否异常")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write"})
    public ApiResponse<java.util.List<com.cretas.aims.dto.inventory.SalesPriceTrendDTO>> getProductPriceTrend(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String productTypeId,
            @RequestParam(defaultValue = "10") int limit) {
        var trend = salesService.getProductPriceTrend(factoryId, productTypeId, limit);
        return ApiResponse.success("查询成功", trend);
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

    // ==================== SP5: 毛利红线校验 ====================

    /**
     * SP5: 报价毛利红线校验 (下单前预警, 非阻断).
     *
     * <p>校验给定品类报价是否低于毛利红线，仅返回 {@code belowRedline} + {@code warningMessage}，
     * 不暴露 minPrice / standardCost / targetGrossMargin 等价格敏感数据。
     *
     * <p><strong>权限 (2026-06-10 修正)</strong>: 改用 {@code @RequirePermission("sales:read_write")}
     * 与"创建订单"端点同一闸门 —— 任何能下单的角色 (含销售/录单员) 都能在提交前调本接口预览毛利预警
     * (fool-proof Rule 1: 提交前看到边界, 而非提交后被拒)。原先 {@code @RequireRole} 仅放
     * super_admin/sales_manager/finance_manager, 把一线录单员排除在外 = "既拦又瞒", 已废弃。
     * 返回 DTO 已脱敏 (只 boolean + 文案, 无成本数值), 给销售看预警不泄露成本结构。
     */
    @PostMapping("/check-margin")
    @Operation(summary = "报价毛利红线校验（SP5，下单前非阻断预警）")
    @RequirePermission("sales:read_write")
    public ApiResponse<GrossMarginCheckResult> checkMargin(
            @PathVariable @NotBlank String factoryId,
            @RequestBody @Valid CheckMarginRequest request) {
        GrossMarginCheckResult result = grossMarginRedlineService.checkMargin(
                factoryId, request.productTypeId(), request.quotedPrice());
        return ApiResponse.success("校验完成", result);
    }

    /** SP5 check-margin 请求体。 */
    public record CheckMarginRequest(
            @NotBlank String productTypeId,
            @NotNull @Positive BigDecimal quotedPrice) {
    }

    // ==================== P1 #33: 销售价 ≥ 研发预估价 校验 ====================

    /**
     * P1 #33: 销售报价 ≥ 研发预估价 跨流校验 (G流 SP10 → E流, 下单前预警, 非阻断).
     *
     * <p>校验给定产品的销售报价是否低于研发预估价 (SP10 QuotationTask 建议售价), 仅返回
     * {@code belowEstimate} + {@code warningMessage}, 不暴露 estimatePrice / suggestedPrice /
     * finalPrice 等价格敏感数据 (脱敏: 只给"是否低于"+文案, 不给绝对值)。
     *
     * <p><strong>权限</strong>: 与 check-margin 同闸 {@code @RequirePermission("sales:read_write")}
     * —— 任何能下单的角色 (含销售/录单员) 都能在提交前调本接口预览估价预警 (fool-proof Rule 1:
     * 提交前看到边界, 而非提交后被拒)。返回 DTO 已脱敏, 给销售看预警不泄露研发预估价/成本结构。
     *
     * <p>研发未报价 (无 QuotationTask 或预估价为空) → {@code belowEstimate=null} (诚实跳过, 不警告)。
     */
    @PostMapping("/check-estimate-price")
    @Operation(summary = "销售价 ≥ 研发预估价 校验（#33，下单前非阻断预警）")
    @RequirePermission("sales:read_write")
    public ApiResponse<EstimatePriceCheckResult> checkEstimatePrice(
            @PathVariable @NotBlank String factoryId,
            @RequestBody @Valid CheckEstimatePriceRequest request) {
        EstimatePriceCheckResult result = estimatePriceCheckService.checkAgainstEstimate(
                factoryId, request.productTypeId(), request.sellingPrice());
        return ApiResponse.success("校验完成", result);
    }

    /** #33 check-estimate-price 请求体。 */
    public record CheckEstimatePriceRequest(
            @NotBlank String productTypeId,
            @NotNull @Positive BigDecimal sellingPrice) {
    }

    // ==================== 改价留痕 + 超阈值预警 (warn-not-block) ====================

    /**
     * POST /orders/{orderId}/lines/{lineId}/adjust-price — 调整销售订单行单价.
     *
     * <p>已发货行拒绝改价 (409).
     * <p>降价 >10% 或 涨价 >20% 时设 flagged=true 审计标记，改价仍立即生效 (warn-not-block).
     * <p>fool-proof Rule 3: reasonType 为 enum dropdown, OTHER 时 reasonDetail 必填.
     */
    @PostMapping("/orders/{orderId}/lines/{lineId}/adjust-price")
    @Operation(summary = "调整销售订单行单价 (改价立即生效，超阈值返 priceWarning)")
    @RequirePermission("sales:read_write")
    public ApiResponse<AdjustPriceResponse> adjustLinePrice(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @PathVariable @NotNull Long lineId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody AdjustPriceRequest request) {
        Long userId = extractUserId(authorization);
        log.info("改价请求: factoryId={}, orderId={}, lineId={}, newPrice={}",
                factoryId, orderId, lineId, request.newUnitPrice());
        AdjustPriceResponse response = priceAdjustmentService.adjustLinePrice(
                factoryId, orderId, lineId, request, userId);
        return ApiResponse.success("改价操作已生效", response);
    }

    /**
     * GET /orders/{orderId}/price-adjustments — 查询销售订单改价历史.
     */
    @GetMapping("/orders/{orderId}/price-adjustments")
    @Operation(summary = "销售订单改价历史")
    @RequirePermission({"sales:read_write", "sales:read"})
    public ApiResponse<java.util.List<SalesPriceAdjustmentRecordDTO>> getPriceAdjustmentHistory(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        java.util.List<SalesPriceAdjustmentRecordDTO> records =
                priceAdjustmentService.getPriceAdjustmentHistory(factoryId, orderId);
        return ApiResponse.success("查询成功", records);
    }

    // ==================== 内部方法 ====================

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }

    public record OpeningFinishedGoodsRequest(
            @NotBlank String productTypeId,
            @NotBlank String batchNumber,
            @NotNull @Positive BigDecimal producedQuantity,
            @NotBlank String unit,
            @NotNull LocalDate productionDate,
            String remark,
            // 气调货入库 (六扇门: "一托36放37, 对方划单"). 以下三项全可选, 留 null = 普通入库.
            // producedQuantity 即实收数 (37); nominalQuantity 为标称数 (36).
            @Positive BigDecimal nominalQuantity,
            Boolean counterpartyConfirmed,
            String inboundRemark) {
    }
}
