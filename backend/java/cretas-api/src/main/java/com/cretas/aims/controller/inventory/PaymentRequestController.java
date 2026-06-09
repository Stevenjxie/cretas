package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.PaymentRequestService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 付款申请 REST 控制器（SP6）
 *
 * <p>状态机: PENDING → FINANCE_REVIEW → APPROVED → PAID / REJECTED
 *
 * <p>markPaid 三写原子：status=PAID + ArApTransaction(AP_PAYMENT) + Supplier.currentBalance 扣减，
 * 均在单一 @Transactional 中完成，任一失败全回滚（详见 PaymentRequestServiceImpl.markPaid()）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/payment-requests")
@RequiredArgsConstructor
@Tag(name = "付款申请管理", description = "SP6 采购付款申请、审批与付款确认")
@RequirePermission({"procurement:read_write", "finance:read_write"})
public class PaymentRequestController {

    private final PaymentRequestService paymentRequestService;
    private final MobileService mobileService;

    // ─── 创建付款申请 ──────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "创建付款申请", description = "同一 PO 已有 PENDING/FINANCE_REVIEW/APPROVED 时返回 409")
    @RequirePermission({"procurement:read_write"})
    public ApiResponse<PaymentRequest> createPaymentRequest(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId(authorization);
        String purchaseOrderId = getString(body, "purchaseOrderId");
        String supplierId = getString(body, "supplierId");
        BigDecimal amount = getBigDecimal(body, "amount");
        String paymentMethod = getString(body, "paymentMethod");
        String remark = getString(body, "remark");

        log.info("[SP6] 创建付款申请: factoryId={}, poId={}, amount={}", factoryId, purchaseOrderId, amount);
        PaymentRequest pr = paymentRequestService.create(
                factoryId, purchaseOrderId, supplierId, amount,
                paymentMethod, userId, remark);
        return ApiResponse.success("付款申请已创建", pr);
    }

    // ─── 提交（PENDING → FINANCE_REVIEW）──────────────────────────────────────

    @PutMapping("/{requestId}/submit")
    @Operation(summary = "提交付款申请至财务审核")
    @RequirePermission({"procurement:read_write"})
    public ApiResponse<PaymentRequest> submit(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        log.info("[SP6] 提交付款申请: factoryId={}, requestId={}, userId={}", factoryId, requestId, userId);
        PaymentRequest pr = paymentRequestService.submit(requestId, userId);
        return ApiResponse.success("已提交财务审核", pr);
    }

    // ─── 财务审批（FINANCE_REVIEW → APPROVED）────────────────────────────────

    @PutMapping("/{requestId}/finance-approve")
    @Operation(summary = "财务审批付款申请")
    @RequirePermission({"finance:read_write"})
    public ApiResponse<PaymentRequest> financeApprove(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String reviewNote = body != null ? body.get("reviewNote") : null;
        log.info("[SP6] 财务审批付款申请: factoryId={}, requestId={}, userId={}", factoryId, requestId, userId);
        PaymentRequest pr = paymentRequestService.financeApprove(requestId, userId, reviewNote);
        return ApiResponse.success("审批通过", pr);
    }

    // ─── 驳回（PENDING|FINANCE_REVIEW → REJECTED）────────────────────────────

    @PutMapping("/{requestId}/reject")
    @Operation(summary = "驳回付款申请")
    @RequirePermission({"finance:read_write", "procurement:read_write"})
    public ApiResponse<PaymentRequest> reject(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String rejectReason = body != null ? body.get("rejectReason") : null;
        log.info("[SP6] 驳回付款申请: factoryId={}, requestId={}, userId={}", factoryId, requestId, userId);
        PaymentRequest pr = paymentRequestService.reject(requestId, userId, rejectReason);
        return ApiResponse.success("已驳回", pr);
    }

    // ─── 确认付款（APPROVED → PAID）：三写原子 ───────────────────────────────

    @PutMapping("/{requestId}/mark-paid")
    @Operation(summary = "出纳确认付款（三写原子：status=PAID + ArApTransaction + Supplier余额）")
    @RequirePermission({"finance:read_write"})
    public ApiResponse<PaymentRequest> markPaid(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String evidence = body != null ? body.get("evidence") : null;
        log.info("[SP6] 确认付款: factoryId={}, requestId={}, userId={}", factoryId, requestId, userId);
        PaymentRequest pr = paymentRequestService.markPaid(requestId, userId, evidence);
        return ApiResponse.success("付款已确认", pr);
    }

    // ─── 查询已审批待付款列表 ─────────────────────────────────────────────────

    @GetMapping("/approved")
    @Operation(summary = "查询已审批待付款列表", description = "出纳台账：status=APPROVED 的付款申请，按 approvedAt 升序")
    @RequirePermission({"finance:read_write", "finance:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<List<PaymentRequest>> listApproved(
            @PathVariable @NotBlank String factoryId) {
        List<PaymentRequest> list = paymentRequestService.listApprovedForPayment(factoryId);
        return ApiResponse.success("查询成功", list);
    }

    // ─── 私有工具 ────────────────────────────────────────────────────────────

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }

    private String getString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString() : null;
    }

    private BigDecimal getBigDecimal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
