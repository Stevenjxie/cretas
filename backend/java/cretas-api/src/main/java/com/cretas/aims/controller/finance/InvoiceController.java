package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * Bug #4 (P1 RBAC, 2026-04-18): Added class-level @RequirePermission so finance
 * endpoints reject dispatcher/operator/quality users who don't have finance:w.
 * Previously the controller had ZERO permission checks — any authenticated user
 * could POST /request, /request-from-order, /{id}/approve, etc and bypass UI
 * menu-hiding. Read-only endpoints (list/detail) accept finance:read fallback.
 * Matches pattern in ArApController.
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/invoices")
@RequiredArgsConstructor
@RequirePermission("finance:read_write")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final com.cretas.aims.repository.InvoiceRecordRepository invoiceRecordRepository;

    @RequireModule("finance_ar")
    @PostMapping("/request")
    @RequirePermission({"finance:read_write", "sales:read_write"})
    public ResponseEntity<?> requestInvoice(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        // D1 fix: null-guard on amount to prevent NPE → 500 during demo
        if (body.get("salesOrderId") == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少必要参数: salesOrderId"));
        }
        if (body.get("amount") == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少必要参数: amount"));
        }
        var record = invoiceService.requestInvoice(
                factoryId,
                (String) body.get("salesOrderId"),
                new BigDecimal(body.get("amount").toString()),
                body.get("taxAmount") != null ? new BigDecimal(body.get("taxAmount").toString()) : BigDecimal.ZERO,
                (String) body.get("invoiceType"),
                userId,
                (String) body.get("remark"));
        return ResponseEntity.ok(Map.of("success", true, "data", record, "message", "开票申请已提交"));
    }

    /**
     * 基于销售订单一键生成开票申请,自动按 tax_rate 分组聚合。
     *
     * V3 P0-3 / G1 — 客户原话: 一笔订单可同时含 9% 原料 + 13% 加工费两个税项,
     * 开票申请必须按税率分组拆分显示。
     *
     * 请求体:
     * <pre>
     * { "salesOrderId": "...", "invoiceType": "NORMAL", "remark": "..." }
     * </pre>
     *
     * 响应里的 record.taxBreakdown 含分组明细供前端按组渲染。
     */
    @RequireModule("finance_ar")
    @PostMapping("/request-from-order")
    @RequirePermission({"finance:read_write", "sales:read_write"})
    public ResponseEntity<?> requestInvoiceFromOrder(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        var record = invoiceService.requestInvoiceFromOrder(
                factoryId,
                (String) body.get("salesOrderId"),
                (String) body.get("invoiceType"),
                userId,
                (String) body.get("remark"));
        return ResponseEntity.ok(Map.of("success", true, "data", record,
                "message", "开票申请已提交 (按税率分组)"));
    }

    @RequireModule("finance_ar")
    @PostMapping("/{invoiceId}/approve")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> approve(
            @PathVariable String factoryId,
            @PathVariable String invoiceId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        var record = invoiceService.approveInvoice(factoryId, invoiceId, userId,
                body != null ? body.get("notes") : null);
        return ResponseEntity.ok(Map.of("success", true, "data", record, "message", "开票申请已审核通过"));
    }

    @RequireModule("finance_ar")
    @PostMapping("/{invoiceId}/reject")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> reject(
            @PathVariable String factoryId,
            @PathVariable String invoiceId,
            @RequestBody Map<String, String> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        var record = invoiceService.rejectInvoice(factoryId, invoiceId, userId, body.get("notes"));
        return ResponseEntity.ok(Map.of("success", true, "data", record, "message", "开票申请已驳回"));
    }

    @RequireModule("finance_ar")
    @PostMapping("/{invoiceId}/issue")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> issue(
            @PathVariable String factoryId,
            @PathVariable String invoiceId,
            @RequestPart(value = "file", required = false) MultipartFile pdfFile,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        var record = invoiceService.issueInvoice(factoryId, invoiceId, pdfFile, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", record, "message", "发票已开具"));
    }

    @GetMapping
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write", "sales:read"})
    public ResponseEntity<?> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InvoiceStatus s = status != null ? InvoiceStatus.valueOf(status) : null;
        var result = invoiceService.listInvoices(factoryId, s,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @GetMapping("/{invoiceId}")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write", "sales:read"})
    public ResponseEntity<?> detail(@PathVariable String factoryId, @PathVariable String invoiceId) {
        return ResponseEntity.ok(Map.of("success", true, "data", invoiceService.getInvoice(factoryId, invoiceId)));
    }

    /** List all invoice records for a sales order — used by sales order detail page tab. */
    @GetMapping("/by-sales-order/{salesOrderId}")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write", "sales:read"})
    public ResponseEntity<?> listBySalesOrder(@PathVariable String factoryId, @PathVariable String salesOrderId) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", invoiceService.listInvoicesBySalesOrder(factoryId, salesOrderId)));
    }

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 — tab 15 (开票 by customer).
     * Paginated, newest first, soft-deleted excluded.
     */
    @GetMapping("/by-customer")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read_write", "sales:read"})
    public ResponseEntity<?> listByCustomer(
            @PathVariable String factoryId,
            @RequestParam String customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        var p = invoiceRecordRepository.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                factoryId, customerId, PageRequest.of(safePage - 1, safeSize));
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "content", p.getContent(),
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages(),
                "page", safePage,
                "size", safeSize
        )));
    }
}
