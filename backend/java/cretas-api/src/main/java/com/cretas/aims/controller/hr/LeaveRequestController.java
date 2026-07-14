package com.cretas.aims.controller.hr;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.hr.LeaveRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 请假申请 REST API.
 *
 * <p>路径: /api/mobile/{factoryId}/hr/leave-requests
 *
 * <p>RBAC:
 * <ul>
 *   <li>员工自助 (create/update/submit/cancel/mine): hr:read+</li>
 *   <li>主管审批 (approve/reject/pending): hr:read_write (manager level)</li>
 *   <li>HR 报表 (all/summary): hr:read_write</li>
 * </ul>
 * 'own vs other' 隔离由 Service 层 loadOwn() 抛 BusinessException 强制.
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-16
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/hr/leave-requests")
@RequiredArgsConstructor
@RequireModule("hr_employee")
public class LeaveRequestController {

    private final LeaveRequestService service;

    /**
     * AUD-5 B-A3 sister sweep (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code leave_requests} table (see {@link LeaveRequest}
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
        LeaveRequest req = service.create(factoryId, userId,
                HrBodyParse.reqEnum(body, "leaveType", "请假类型", LeaveType.class),
                HrBodyParse.reqDate(body, "startDate", "开始日期"),
                HrBodyParse.reqDate(body, "endDate", "结束日期"),
                HrBodyParse.reqDecimal(body, "durationHours", "请假时长(小时)"),
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
        LeaveRequest req = service.update(factoryId, userId, id,
                HrBodyParse.reqEnum(body, "leaveType", "请假类型", LeaveType.class),
                HrBodyParse.reqDate(body, "startDate", "开始日期"),
                HrBodyParse.reqDate(body, "endDate", "结束日期"),
                HrBodyParse.reqDecimal(body, "durationHours", "请假时长(小时)"),
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
        Page<LeaveRequest> p = service.listMine(factoryId, userId, status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping("/pending")
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> pending(@PathVariable String factoryId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Page<LeaveRequest> p = service.listPending(factoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "submittedAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> all(@PathVariable String factoryId,
                                 @RequestParam(required = false) LeaveType leaveType,
                                 @RequestParam(required = false) HrRequestStatus status,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        Page<LeaveRequest> p = service.listAll(factoryId, leaveType, status,
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
                    "请假原因最长 " + REASON_MAX_LENGTH + " 字符 (当前 " + reasonStr.length() + ")")
                    .withHint("请使用更短的请假原因 (上限 1000 字符)")
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
