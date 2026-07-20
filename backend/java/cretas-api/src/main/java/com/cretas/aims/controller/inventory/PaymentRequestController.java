package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.PaymentRequestApprovedDTO;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.exception.BusinessException;
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
    @Operation(summary = "旧采购付款申请入口（已停用）",
            description = "采购不再手工创建付款申请；财务从应付账款处理付款")
    @RequirePermission({"procurement:read_write"})
    public ApiResponse<PaymentRequest> createPaymentRequest(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body) {
        throw legacyWriteDisabled("采购付款申请");
    }

    // ─── 创建销售方向付款申请（#29 对客户 outbound 退款/返利/销售费用）─────────────

    /**
     * #29 销售方向付款申请：sourceType=SALES，对客户的 outbound 付款。
     *
     * <p>入参：salesOrderId（可空，无单退款/费用场景）、customerId（必填）、amount、paymentMethod、remark。
     * 同一 salesOrderId 已有活跃销售付款申请时返回 409。后续走同一审批/付款状态机。
     *
     * <p>权限：sales:read_write（销售侧发起）。后续 finance-approve / mark-paid 仍由 finance:read_write 把关。
     */
    @PostMapping("/sales")
    @Operation(summary = "创建销售方向付款申请（#29，对客户退款/返利/销售费用）",
            description = "sourceType=SALES，关联 salesOrderId/customerId；同一 SO 已有活跃销售付款申请返回 409")
    @RequirePermission({"sales:read_write"})
    public ApiResponse<PaymentRequest> createSalesPaymentRequest(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId(authorization);
        String salesOrderId = getString(body, "salesOrderId");
        String customerId = getString(body, "customerId");
        BigDecimal amount = getBigDecimal(body, "amount");
        String paymentMethod = getString(body, "paymentMethod");
        String remark = getString(body, "remark");

        log.info("[#29] 创建销售付款申请: factoryId={}, soId={}, customerId={}, amount={}",
                factoryId, salesOrderId, customerId, amount);
        PaymentRequest pr = paymentRequestService.createSalesPayment(
                factoryId, salesOrderId, customerId, amount,
                paymentMethod, userId, remark);
        return ApiResponse.success("销售付款申请已创建", pr);
    }

    // ─── 提交（PENDING → FINANCE_REVIEW）──────────────────────────────────────

    @PutMapping("/{requestId}/submit")
    @Operation(summary = "旧提交付款审批入口（已停用）")
    @RequirePermission({"procurement:read_write", "sales:read_write"})
    public ApiResponse<PaymentRequest> submit(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization) {
        throw legacyWriteDisabled("提交付款审批");
    }

    // ─── 财务审批（FINANCE_REVIEW → APPROVED）────────────────────────────────

    @PutMapping("/{requestId}/finance-approve")
    @Operation(summary = "旧付款审批通过入口（已停用）")
    @RequirePermission({"finance:read_write"})
    public ApiResponse<PaymentRequest> financeApprove(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        throw legacyWriteDisabled("付款审批通过");
    }

    // ─── 驳回（PENDING|FINANCE_REVIEW → REJECTED）────────────────────────────

    @PutMapping("/{requestId}/reject")
    @Operation(summary = "旧付款审批驳回入口（已停用）")
    @RequirePermission({"finance:read_write", "procurement:read_write", "sales:read_write"})
    public ApiResponse<PaymentRequest> reject(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        throw legacyWriteDisabled("付款审批驳回");
    }

    // ─── 确认付款（APPROVED → PAID）：三写原子 ───────────────────────────────

    @PutMapping("/{requestId}/mark-paid")
    @Operation(summary = "旧确认付款入口（已停用）")
    @RequirePermission({"finance:read_write"})
    public ApiResponse<PaymentRequest> markPaid(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String requestId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        throw legacyWriteDisabled("确认付款");
    }

    // ─── 查询已审批待付款列表 ─────────────────────────────────────────────────

    /**
     * D-9 G1 出纳付款视图：返回带供应商名/PO单号/原料明细的结构化 DTO，不再是裸实体。
     *
     * <p>权限：finance:read_write（含出纳角色）+ procurement 读权限。
     * 本 DTO 中价格字段为 PO 行明细，非 {@code @PriceSensitive} 标注字段，
     * PriceFieldResponseAdvice 不做反射剥除。finance 角色（canViewFinance=true）
     * 已触发 early-return，即使未持有 procurement:price:view 也可见。
     */
    @GetMapping("/approved")
    @Operation(summary = "查询已审批待付款列表（G1出纳视图）",
            description = "出纳台账：status=APPROVED，按 approvedAt 升序，带供应商名/PO单号/明细行")
    @RequirePermission({"finance:read_write", "finance:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<List<PaymentRequestApprovedDTO>> listApproved(
            @PathVariable @NotBlank String factoryId) {
        List<PaymentRequestApprovedDTO> list =
                paymentRequestService.listApprovedForPaymentWithDetails(factoryId);
        return ApiResponse.success("查询成功", list);
    }

    // ─── 查询全部付款申请列表（web 管理后台通用列表）───────────────────────────

    /**
     * 全量列表，支持 status 过滤和关键词搜索（供 web-admin 付款申请管理页使用）。
     *
     * <p>SP6 web list.vue 调用此端点。原控制器只有 /approved（出纳专用），
     * 此端点补全 web 管理侧的全状态浏览 + 筛选能力。
     */
    @GetMapping
    @Operation(summary = "查询付款申请列表（全状态，web 管理后台）",
            description = "支持 status 过滤；keyword 模糊匹配供应商名称或申请单号")
    @RequirePermission({"finance:read_write", "finance:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<List<PaymentRequest>> listAll(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        log.info("[SP6] 查询付款申请列表: factoryId={}, status={}, keyword={}", factoryId, status, keyword);
        List<PaymentRequest> list = paymentRequestService.listByFactory(factoryId, status, keyword);
        return ApiResponse.success("查询成功", list);
    }

    // ─── 私有工具 ────────────────────────────────────────────────────────────

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }

    /**
     * Legacy business-page payment writes are deliberately fail-closed.
     * Payment execution now starts from an audited AP open item, while approval
     * transitions are performed by the OA workflow adapter at its current node.
     * Keeping the old URLs as explicit tombstones prevents stale clients from
     * silently creating unallocated payments during the migration window.
     */
    private BusinessException legacyWriteDisabled(String action) {
        return new BusinessException(
                410, action + "入口已停用，不能从采购或业务详情直接执行")
                .withCode("PAYMENT_REQUEST_LEGACY_WRITE_DISABLED")
                .withHint("请从财务应付账款选择未清应付进行付款；审批请前往 OA 审批中心")
                .withSeverity("BLOCKING");
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
