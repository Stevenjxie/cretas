package com.cretas.aims.controller.hr;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.hr.ExpenseRequest;
import com.cretas.aims.entity.hr.enums.ExpenseCategory;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.hr.ExpenseRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/hr/expense-requests")
@RequiredArgsConstructor
@RequireModule("hr_employee")
public class ExpenseRequestController {

    private final ExpenseRequestService service;

    /**
     * AUD-5 B-A3 sister sweep (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code expense_requests} table (see {@link ExpenseRequest}
     * {@code @Column(length=...)}). Without these, over-length input lets the request
     * reach PG and surfaces as {@code DataIntegrityViolationException} → generic 409
     * "数据处理异常". Pre-check at controller boundary delivers a specific 400 with a
     * hintTarget instead. Mirrors PR #48 / PR #76 pattern.
     */
    private static final int REASON_MAX_LENGTH = 1000;
    private static final int REJECT_REASON_MAX_LENGTH = 500;

    @PostMapping
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> create(@PathVariable String factoryId,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep: length pre-check for reason (VARCHAR 1000) BEFORE
        // dispatching to service layer where PG would surface a generic 409.
        validateReasonLength(body);
        ExpenseRequest req = service.create(factoryId, userId,
                HrBodyParse.reqEnum(body, "category", "报销类别", ExpenseCategory.class),
                HrBodyParse.reqDecimal(body, "amount", "报销金额"),
                HrBodyParse.reqDate(body, "expenseDate", "费用日期"),
                (String) body.get("reason"));
        return ResponseEntity.ok(Map.of("success", true, "data", req, "message", "已保存草稿"));
    }

    @PutMapping("/{id}")
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> update(@PathVariable String factoryId,
                                    @PathVariable String id,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep (Rule 16: entry-point matrix — update path).
        validateReasonLength(body);
        ExpenseRequest req = service.update(factoryId, userId, id,
                HrBodyParse.reqEnum(body, "category", "报销类别", ExpenseCategory.class),
                HrBodyParse.reqDecimal(body, "amount", "报销金额"),
                HrBodyParse.reqDate(body, "expenseDate", "费用日期"),
                (String) body.get("reason"));
        return ResponseEntity.ok(Map.of("success", true, "data", req, "message", "已更新"));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> submit(@PathVariable String factoryId,
                                    @PathVariable String id,
                                    @RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.submit(factoryId, userId, id), "message", "已提交审批"));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> approve(@PathVariable String factoryId,
                                     @PathVariable String id,
                                     @RequestAttribute("userId") Long approverId) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.approve(factoryId, approverId, id), "message", "已批准"));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> reject(@PathVariable String factoryId,
                                    @PathVariable String id,
                                    @RequestAttribute("userId") Long approverId,
                                    @RequestBody Map<String, String> body) {
        // AUD-5 B-A3 sister sweep: reject_reason column is VARCHAR(500), not 1000.
        validateRejectReasonLength(body.get("reason"));
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.reject(factoryId, approverId, id, body.get("reason")),
                "message", "已拒绝"));
    }

    @PostMapping("/{id}/cancel")
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> cancel(@PathVariable String factoryId,
                                    @PathVariable String id,
                                    @RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.cancel(factoryId, userId, id), "message", "已撤回"));
    }

    /**
     * 财务标记付款 — Day 12 PaymentRecord 联动后此端点可由 PaymentRecordService.verify
     * 回调触发. 当前手工调用接受 {paymentRecordId} 或 null (走旧式手工记账).
     */
    @PostMapping("/{id}/mark-paid")
    @RequirePermission({"hr:read_write", "finance:read_write"})
    public ResponseEntity<?> markPaid(@PathVariable String factoryId,
                                      @PathVariable String id,
                                      @RequestBody(required = false) Map<String, String> body) {
        String pid = body == null ? null : body.get("paymentRecordId");
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.markPaid(factoryId, id, pid), "message", "已标记付款"));
    }

    @GetMapping("/{id}")
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> get(@PathVariable String factoryId, @PathVariable String id) {
        return service.getById(factoryId, id)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of("success", true, "data", r)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("success", false, "message", "申请不存在")));
    }

    @GetMapping("/mine")
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> mine(@PathVariable String factoryId,
                                  @RequestAttribute("userId") Long userId,
                                  @RequestParam(required = false) HrRequestStatus status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        Page<ExpenseRequest> p = service.listMine(factoryId, userId, status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping("/pending")
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> pending(@PathVariable String factoryId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Page<ExpenseRequest> p = service.listPending(factoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "submittedAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> all(@PathVariable String factoryId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        Page<ExpenseRequest> p = service.listAll(factoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping("/summary")
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> summary(@PathVariable String factoryId,
                                     @RequestParam String yearMonth) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.summarizeMonth(factoryId, yearMonth)));
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep) ====================

    /**
     * AUD-5 B-A3 sister sweep (edge audit 2026-05-20): length pre-check for
     * {@code reason} (PG VARCHAR 1000). Used by create + update paths
     * (Rule 16: entry-point matrix). Mirrors PR #48 {@code RULE_NAME_MAX_LENGTH} pattern.
     *
     * <p>Without this, an over-length input lets the request reach PG and surfaces as
     * {@link org.springframework.dao.DataIntegrityViolationException} → generic 409
     * "数据处理异常". Pre-check delivers a specific 400 with the actual vs allowed length.
     */
    private void validateReasonLength(Map<String, Object> body) {
        Object reason = body.get("reason");
        if (reason instanceof String reasonStr && reasonStr.length() > REASON_MAX_LENGTH) {
            throw new BusinessException(400,
                    "报销说明最长 " + REASON_MAX_LENGTH + " 字符 (当前 " + reasonStr.length() + ")")
                    .withHint("请使用更短的报销说明 (上限 1000 字符)")
                    .withSeverity("warning")
                    .withHintTarget("reason");
        }
    }

    /**
     * AUD-5 B-A3 sister sweep: length pre-check for reject reason (PG VARCHAR 500).
     * The reject_reason column has a tighter cap than the request reason column (1000),
     * so we use a separate constant.
     */
    private void validateRejectReasonLength(String reason) {
        if (reason != null && reason.length() > REJECT_REASON_MAX_LENGTH) {
            throw new BusinessException(400,
                    "驳回原因最长 " + REJECT_REASON_MAX_LENGTH + " 字符 (当前 " + reason.length() + ")")
                    .withHint("请使用更短的驳回原因 (上限 500 字符)")
                    .withSeverity("warning")
                    .withHintTarget("reason");
        }
    }
}
