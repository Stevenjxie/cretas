package com.cretas.aims.controller.hr;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.hr.OvertimeRequest;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.OvertimeType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.hr.OvertimeRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/hr/overtime-requests")
@RequiredArgsConstructor
@RequireModule("hr_employee")
public class OvertimeRequestController {

    private final OvertimeRequestService service;

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code overtime_requests} table (see {@link OvertimeRequest}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
     */
    private static final int REASON_MAX_LENGTH = 1000;
    private static final int REJECT_REASON_MAX_LENGTH = 500;

    @PostMapping
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> create(@PathVariable String factoryId,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep batch 3: length pre-check for reason (VARCHAR 1000) BEFORE
        // dispatching to service layer where PG would surface a generic 409.
        validateReasonLength(body);
        OvertimeRequest req = service.create(factoryId, userId,
                HrBodyParse.reqEnum(body, "overtimeType", "加班类型", OvertimeType.class),
                HrBodyParse.reqDateTime(body, "startTime", "开始时间"),
                HrBodyParse.reqDateTime(body, "endTime", "结束时间"),
                HrBodyParse.reqDecimal(body, "hours", "加班时长(小时)"),
                HrBodyParse.reqEnum(body, "compensationType", "补偿方式", CompensationType.class),
                (String) body.get("reason"));
        return ResponseEntity.ok(Map.of("success", true, "data", req, "message", "已保存草稿"));
    }

    @PutMapping("/{id}")
    @RequirePermission({"hr:read", "hr:read_write"})
    public ResponseEntity<?> update(@PathVariable String factoryId,
                                    @PathVariable String id,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — update path).
        validateReasonLength(body);
        OvertimeRequest req = service.update(factoryId, userId, id,
                HrBodyParse.reqEnum(body, "overtimeType", "加班类型", OvertimeType.class),
                HrBodyParse.reqDateTime(body, "startTime", "开始时间"),
                HrBodyParse.reqDateTime(body, "endTime", "结束时间"),
                HrBodyParse.reqDecimal(body, "hours", "加班时长(小时)"),
                HrBodyParse.reqEnum(body, "compensationType", "补偿方式", CompensationType.class),
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
        // AUD-5 B-A3 sister sweep batch 3: reject_reason column is VARCHAR(500), not 1000.
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
        Page<OvertimeRequest> p = service.listMine(factoryId, userId, status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping("/pending")
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> pending(@PathVariable String factoryId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Page<OvertimeRequest> p = service.listPending(factoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "submittedAt")));
        return ResponseEntity.ok(Map.of("success", true, "data", p));
    }

    @GetMapping
    @RequirePermission({"hr:read_write"})
    public ResponseEntity<?> all(@PathVariable String factoryId,
                                 @RequestParam(required = false) OvertimeType overtimeType,
                                 @RequestParam(required = false) CompensationType compensationType,
                                 @RequestParam(required = false) HrRequestStatus status,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        Page<OvertimeRequest> p = service.listAll(factoryId, overtimeType, compensationType, status,
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

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 3) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): length pre-check for
     * {@code reason} (PG VARCHAR 1000). Used by create + update paths
     * (Rule 16: entry-point matrix). Mirrors PR #48 / PR #78 {@code REASON_MAX_LENGTH} pattern.
     *
     * <p>Without this, an over-length input lets the request reach PG and surfaces as
     * {@link org.springframework.dao.DataIntegrityViolationException} → generic 409
     * "数据处理异常". Pre-check delivers a specific 400 with the actual vs allowed length.
     */
    private void validateReasonLength(Map<String, Object> body) {
        Object reason = body.get("reason");
        if (reason instanceof String reasonStr && reasonStr.length() > REASON_MAX_LENGTH) {
            throw new BusinessException(400,
                    "加班原因最长 " + REASON_MAX_LENGTH + " 字符 (当前 " + reasonStr.length() + ")")
                    .withHint("请使用更短的加班原因 (上限 1000 字符)")
                    .withSeverity("warning")
                    .withHintTarget("reason");
        }
    }

    /**
     * AUD-5 B-A3 sister sweep batch 3: length pre-check for reject reason (PG VARCHAR 500).
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
