package com.cretas.aims.controller.sales;

import com.cretas.aims.entity.sales.OperationalQuote;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.sales.OperationalQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * 销售运营报价 Controller — V3 P0-4 (Round 2 Agent B 设计).
 *
 * <h3>客户原话锚点</h3>
 * 会议 1670-1750s: "样品过了以后, 像类似于提交一个审批, 对应的人员去报价的;
 * 运营报完价以后调过去审批审批完 ok 没问题, 然后给到销售这边."
 *
 * <h3>状态机</h3>
 * <pre>
 *   POST  /quotes                                       → PENDING_QUOTE
 *   PUT   /quotes/{id}/submit-price (销售运营录价)        → PENDING_APPROVAL
 *   PUT   /quotes/{id}/approve      (主管审批)            → APPROVED
 *   PUT   /quotes/{id}/reject       (主管驳回)            → REJECTED
 *   PUT   /quotes/{id}/revise       (客户砍价后重新报价)   → PENDING_QUOTE
 * </pre>
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/quotes")
@RequiredArgsConstructor
public class OperationalQuoteController {

    private final OperationalQuoteService quoteService;
    private final com.cretas.aims.repository.sales.OperationalQuoteRepository quoteRepository;

    /** 研发提交报价单 — 由样品驱动 */
    @RequirePermission({"sales:read_write"})
    @RequireModule("sales_order")
    @PostMapping
    public ResponseEntity<?> createQuote(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        String customerId = (String) body.get("customerId");
        String productTypeId = (String) body.get("productTypeId");
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "customerId 不能为空"));
        }
        if (productTypeId == null || productTypeId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "productTypeId 不能为空"));
        }
        OperationalQuote quote = quoteService.createQuote(
                factoryId,
                (String) body.get("sampleId"),
                (String) body.get("customerId"),
                (String) body.get("productTypeId"),
                (String) body.get("bomId"),
                userId,
                body.get("quotedByUserId") != null ? Long.valueOf(body.get("quotedByUserId").toString()) : null,
                (String) body.get("quotedByName"),
                (String) body.get("remarks"));
        return ResponseEntity.ok(Map.of("success", true, "data", quote, "message", "报价单已创建, 已分配给销售运营"));
    }

    /** 销售运营录价 → PENDING_APPROVAL */
    @RequirePermission({"sales:read_write"})
    @RequireModule("sales_order")
    @PutMapping("/{quoteId}/submit-price")
    public ResponseEntity<?> submitPrice(
            @PathVariable String factoryId,
            @PathVariable String quoteId,
            @RequestBody Map<String, Object> body) {
        OperationalQuote quote = quoteService.submitQuote(
                factoryId, quoteId,
                (String) body.get("quoteType"),
                body.get("unitPrice") != null ? new BigDecimal(body.get("unitPrice").toString()) : null,
                (String) body.get("unit"),
                body.get("minOrderQty") != null ? new BigDecimal(body.get("minOrderQty").toString()) : null,
                body.get("costPrice") != null ? new BigDecimal(body.get("costPrice").toString()) : null,
                body.get("validUntil") != null ? LocalDate.parse(body.get("validUntil").toString()) : null,
                (String) body.get("remarks"));
        return ResponseEntity.ok(Map.of("success", true, "data", quote, "message", "报价已录入, 等待主管审批"));
    }

    /** 主管审批通过 → APPROVED */
    @RequirePermission({"sales:read_write"})
    @RequireModule("sales_order")
    @PutMapping("/{quoteId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable String factoryId,
            @PathVariable String quoteId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        String approverName = body != null ? (String) body.get("approverName") : null;
        String comment = body != null ? (String) body.get("comment") : null;
        OperationalQuote quote = quoteService.approveQuote(factoryId, quoteId, userId, approverName, comment);
        return ResponseEntity.ok(Map.of("success", true, "data", quote, "message", "报价已审批通过, 销售可下单"));
    }

    /** 主管驳回 → REJECTED */
    @RequirePermission({"sales:read_write"})
    @RequireModule("sales_order")
    @PutMapping("/{quoteId}/reject")
    public ResponseEntity<?> reject(
            @PathVariable String factoryId,
            @PathVariable String quoteId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        OperationalQuote quote = quoteService.rejectQuote(
                factoryId, quoteId, userId,
                (String) body.get("approverName"),
                (String) body.get("reason"));
        return ResponseEntity.ok(Map.of("success", true, "data", quote, "message", "报价已驳回"));
    }

    /** 客户砍价后重新报价 → PENDING_QUOTE */
    @RequirePermission({"sales:read_write"})
    @RequireModule("sales_order")
    @PutMapping("/{quoteId}/revise")
    public ResponseEntity<?> revise(
            @PathVariable String factoryId,
            @PathVariable String quoteId,
            @RequestBody Map<String, Object> body) {
        OperationalQuote quote = quoteService.reviseQuote(factoryId, quoteId, (String) body.get("reason"));
        return ResponseEntity.ok(Map.of("success", true, "data", quote, "message", "已发起重新报价"));
    }

    /** 详情 (factory-scoped) */
    @GetMapping("/{quoteId}")
    public ResponseEntity<?> getQuote(@PathVariable String factoryId, @PathVariable String quoteId) {
        return ResponseEntity.ok(Map.of("success", true, "data", quoteService.getQuote(factoryId, quoteId)));
    }

    /** 列表 — 支持按 status 过滤 */
    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = quoteService.listQuotes(factoryId, status,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /** 销售下单时拉取有效报价 (APPROVED + 未过期 + 同客户同产品) */
    @GetMapping("/active")
    public ResponseEntity<?> getActive(
            @PathVariable String factoryId,
            @RequestParam String customerId,
            @RequestParam String productTypeId) {
        var quotes = quoteService.getActiveQuotes(factoryId, customerId, productTypeId);
        return ResponseEntity.ok(Map.of("success", true, "data", quotes));
    }

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 — tab 9 (报价单 by customer).
     * Paginated, newest first, soft-deleted excluded.
     */
    @GetMapping("/by-customer")
    public ResponseEntity<?> listByCustomer(
            @PathVariable String factoryId,
            @RequestParam String customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        var p = quoteRepository.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                factoryId, customerId,
                org.springframework.data.domain.PageRequest.of(safePage - 1, safeSize));
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "content", p.getContent(),
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages(),
                "page", safePage,
                "size", safeSize
        )));
    }
}
