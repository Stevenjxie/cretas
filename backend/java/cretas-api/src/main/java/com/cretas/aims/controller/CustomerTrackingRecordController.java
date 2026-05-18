package com.cretas.aims.controller;

import com.cretas.aims.entity.CustomerTrackingRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerTrackingRecordRepository;
import com.cretas.aims.annotation.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * S-CRM-1 (Sprint 4 Wave 2 Chat L) — customer tracking record REST endpoints.
 *
 * Entity + repository already shipped in Sprint 1. PR 8 adds the Controller
 * + DTO layer and RBAC gating. All endpoints scoped by {factoryId} path var.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/sales/customer-tracking")
@RequiredArgsConstructor
public class CustomerTrackingRecordController {

    private final CustomerTrackingRecordRepository repository;

    /**
     * GET /api/mobile/{factoryId}/sales/customer-tracking
     *   ?customerId=...   list by customer (newest first, paginated)
     *   ?page=0&size=20   pagination params
     */
    @GetMapping
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        if (customerId != null && !customerId.isBlank()) {
            Page<CustomerTrackingRecord> p = repository.findByCustomerIdAndDeletedAtIsNull(
                    customerId, PageRequest.of(page, safeSize));
            // Filter by factory in-memory to defend tenant isolation alongside repo customer filter.
            List<CustomerTrackingRecord> safe = p.getContent().stream()
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .toList();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of(
                            "content", safe,
                            "totalElements", p.getTotalElements(),
                            "totalPages", p.getTotalPages()
                    )
            ));
        }
        List<CustomerTrackingRecord> all = repository.findByFactoryIdAndDeletedAtIsNullOrderByRecordTimeDesc(factoryId);
        int from = Math.min(page * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "content", all.subList(from, to),
                        "totalElements", all.size(),
                        "totalPages", (int) Math.ceil(all.size() / (double) safeSize)
                )
        ));
    }

    /** GET single record. */
    @GetMapping("/{id}")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String factoryId, @PathVariable Long id) {
        CustomerTrackingRecord r = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Tracking record not found: " + id));
        if (!factoryId.equals(r.getFactoryId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "factoryId mismatch"
            ));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", r));
    }

    /**
     * POST create.
     *
     * Sprint 4 W1 S-CUSTOMER-TAB-1 防呆 R4: 5min 内同 (factoryId, customerId, content) 二次创建
     * → BusinessException(409) with actionHint to existing record (GlobalExceptionHandler 自动映射 HTTP 409).
     */
    @PostMapping
    @RequirePermission("sales:edit")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateTrackingRecordRequest body
    ) {
        LocalDateTime now = LocalDateTime.now();
        // R4 dedup
        var recent = repository.findRecentByContent(
                factoryId, body.getCustomerId(), body.getContent(), now.minusMinutes(5));
        if (!recent.isEmpty()) {
            Long existingId = recent.get(0).getId();
            throw new BusinessException(409,
                    "5 分钟内已创建过相同内容的跟踪记录 (id=" + existingId + "). 请避免重复提交.")
                    .withHint("/sales/customers/" + body.getCustomerId() + "?tab=tracking&highlight=" + existingId);
        }

        CustomerTrackingRecord r = new CustomerTrackingRecord();
        r.setFactoryId(factoryId);
        r.setCustomerId(body.getCustomerId());
        r.setRecordTime(body.getRecordTime() != null ? body.getRecordTime() : now);
        r.setRecorderName(body.getRecorderName());
        r.setRecorderId(body.getRecorderId());
        r.setContent(body.getContent());
        r.setContactPerson(body.getContactPerson());
        r.setContactPhone(body.getContactPhone());
        r.setAddress(body.getAddress());
        r.setRemark(body.getRemark());
        // R3 trackingType: validate enum membership if provided (frontend dropdown enforces, backend defense)
        if (body.getTrackingType() != null && !body.getTrackingType().isBlank()) {
            String tt = body.getTrackingType();
            if (!java.util.Set.of("PHONE", "WECHAT", "EMAIL", "VISIT", "VIDEO", "OTHER").contains(tt)) {
                throw new BusinessException(400, "trackingType 必须是 PHONE/WECHAT/EMAIL/VISIT/VIDEO/OTHER 之一");
            }
            r.setTrackingType(tt);
        }
        repository.save(r);
        log.info("Created tracking record {} for customer {} (factory={}) type={}",
                r.getId(), body.getCustomerId(), factoryId, body.getTrackingType());
        return ResponseEntity.ok(Map.of("success", true, "data", r));
    }

    /**
     * PUT update — Sprint 4 W1 S-CUSTOMER-TAB-1 tab 1 编辑功能.
     * 部分更新: 仅修改 content / contactPerson / contactPhone / address / remark
     * 不动 factoryId / customerId / recorderId / recordTime (防止误改归属/时间).
     */
    @PutMapping("/{id}")
    @RequirePermission("sales:edit")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTrackingRecordRequest body
    ) {
        CustomerTrackingRecord existing = repository.findById(id).orElseThrow(
                () -> new BusinessException(404, "跟踪记录不存在: " + id));
        if (!factoryId.equals(existing.getFactoryId())) {
            throw new BusinessException(403, "无权操作此记录 (factoryId mismatch)");
        }
        if (existing.getDeletedAt() != null) {
            throw new BusinessException(410, "记录已删除, 无法修改");
        }

        if (body.getContent() != null) existing.setContent(body.getContent());
        if (body.getContactPerson() != null) existing.setContactPerson(body.getContactPerson());
        if (body.getContactPhone() != null) existing.setContactPhone(body.getContactPhone());
        if (body.getAddress() != null) existing.setAddress(body.getAddress());
        if (body.getRemark() != null) existing.setRemark(body.getRemark());
        if (body.getTrackingType() != null) {
            String tt = body.getTrackingType();
            if (!java.util.Set.of("PHONE", "WECHAT", "EMAIL", "VISIT", "VIDEO", "OTHER").contains(tt)) {
                throw new BusinessException(400, "trackingType 必须是 PHONE/WECHAT/EMAIL/VISIT/VIDEO/OTHER 之一");
            }
            existing.setTrackingType(tt);
        }

        repository.save(existing);
        return ResponseEntity.ok(Map.of("success", true, "data", existing));
    }

    /** DELETE soft-delete (SQLDelete on entity). */
    @DeleteMapping("/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String factoryId, @PathVariable Long id) {
        CustomerTrackingRecord r = repository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Tracking record not found: " + id));
        if (!factoryId.equals(r.getFactoryId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "factoryId mismatch"
            ));
        }
        repository.delete(r);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("id", id)));
    }

    @Data
    public static class CreateTrackingRecordRequest {
        @NotBlank
        private String customerId;
        private LocalDateTime recordTime;
        private String recorderName;
        private Long recorderId;
        @NotBlank
        private String content;
        private String contactPerson;
        private String contactPhone;
        private String address;
        private String remark;
        /** R3 dropdown enum: PHONE / WECHAT / EMAIL / VISIT / VIDEO / OTHER */
        private String trackingType;
    }

    /** Sprint 4 W1 S-CUSTOMER-TAB-1 PUT body — all fields optional (partial update). */
    @Data
    public static class UpdateTrackingRecordRequest {
        private String content;
        private String contactPerson;
        private String contactPhone;
        private String address;
        private String remark;
        /** R3 dropdown enum: PHONE / WECHAT / EMAIL / VISIT / VIDEO / OTHER */
        private String trackingType;
    }
}
