package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.PurchaseInvoiceService;
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
 * 采购发票 REST 控制器（SP6）
 *
 * <p>上传发票 → 对账（MATCHED/MISMATCHED）→ 逾期提醒。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/invoices")
@RequiredArgsConstructor
@Tag(name = "采购发票管理", description = "SP6 发票上传、对账与逾期提醒")
@RequirePermission({"finance:read_write", "procurement:read_write"})
public class PurchaseInvoiceController {

    private final PurchaseInvoiceService purchaseInvoiceService;
    private final MobileService mobileService;

    // ─── 上传发票 ──────────────────────────────────────────────────────────────

    @PostMapping("/upload")
    @Operation(summary = "上传采购发票", description = "发票号在工厂内唯一（相同发票号返回 409）")
    public ApiResponse<PurchaseInvoice> upload(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body) {
        Long userId = extractUserId(authorization);
        String poId = getString(body, "purchaseOrderId");
        String supplierId = getString(body, "supplierId");
        String invoiceNumber = getString(body, "invoiceNumber");
        BigDecimal amount = getBigDecimal(body, "amount");
        String ocrRawResult = getString(body, "ocrRawResult");

        log.info("[SP6] 上传发票: factoryId={}, poId={}, invoiceNumber={}", factoryId, poId, invoiceNumber);
        PurchaseInvoice invoice = purchaseInvoiceService.upload(
                factoryId, poId, supplierId, invoiceNumber, amount, ocrRawResult, userId);
        return ApiResponse.success("发票上传成功", invoice);
    }

    // ─── 对账 ─────────────────────────────────────────────────────────────────

    @PutMapping("/{invoiceId}/reconcile")
    @Operation(summary = "发票对账", description = "reconcileStatus: MATCHED（对账通过）/ MISMATCHED（不一致）")
    public ApiResponse<Void> reconcile(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String invoiceId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String reconcileStatus = body.get("reconcileStatus");
        String note = body.get("note");

        if (!"MATCHED".equals(reconcileStatus) && !"MISMATCHED".equals(reconcileStatus)) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    "无效的对账状态: " + reconcileStatus + "，可选值: MATCHED / MISMATCHED");
        }

        log.info("[SP6] 发票对账: factoryId={}, invoiceId={}, status={}", factoryId, invoiceId, reconcileStatus);
        purchaseInvoiceService.reconcile(invoiceId, factoryId, reconcileStatus, note, userId);
        return ApiResponse.success("对账完成", null);
    }

    // ─── 逾期提醒 ─────────────────────────────────────────────────────────────

    @GetMapping("/reminder")
    @Operation(summary = "查询待开票 PO 逾期提醒", description = "返回已超 invoice_reminder_days 且发票仍为 PENDING 的 PO ID 列表")
    @RequirePermission({"finance:read_write", "finance:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<List<String>> pendingReminder(
            @PathVariable @NotBlank String factoryId) {
        List<String> poIds = purchaseInvoiceService.listPendingReminder(factoryId);
        return ApiResponse.success("查询成功", poIds);
    }

    // ─── 按 PO 查询发票列表 ────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "按采购订单查询发票列表", description = "必须传 poId 参数")
    @RequirePermission({"finance:read_write", "finance:read", "procurement:read_write", "procurement:read"})
    public ApiResponse<List<PurchaseInvoice>> listByPurchaseOrder(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String poId) {
        List<PurchaseInvoice> list = purchaseInvoiceService.listByPurchaseOrder(factoryId, poId);
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
