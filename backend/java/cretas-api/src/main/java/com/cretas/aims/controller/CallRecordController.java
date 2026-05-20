package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.CallRecord;
import com.cretas.aims.entity.enums.CallType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.CallRecordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通话记录 REST controller (Sprint 6 W2-C-2, 2026-05-19).
 *
 * <p>Base URL: {@code /api/mobile/{factoryId}/customer/{customerId}/call-record}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST   — create new call record (multipart/form-data with optional audio file)</li>
 *   <li>GET    — paginated list by customer (newest first)</li>
 *   <li>GET /{id}    — single record (frontend polls for transcript status)</li>
 *   <li>PUT /{id}    — partial update (contactName/contactPhone/remark/durationSec)</li>
 *   <li>DELETE /{id} — soft delete</li>
 * </ul>
 *
 * <p>RBAC: sales:view (read) / sales:edit (write).
 *
 * <p>Response 统一格式 (per .claude/rules/api-response-handling.md):
 * <pre>{ "success": true, "data": ..., "message": "..." }</pre>
 *
 * <p>POST accepts multipart/form-data with these parts:
 * <ul>
 *   <li>{@code audio} (optional File): audio file (mp3/wav/m4a/ogg/webm, max 50MB)</li>
 *   <li>{@code callType} (String, required): INCOMING / OUTGOING / MISSED</li>
 *   <li>{@code callTime} (ISO datetime, optional): default = now</li>
 *   <li>{@code durationSec} (Integer, optional): call length seconds</li>
 *   <li>{@code contactName} (String, optional)</li>
 *   <li>{@code contactPhone} (String, optional)</li>
 *   <li>{@code remark} (String, optional)</li>
 *   <li>{@code createdBy} (Long, required): uploader user ID</li>
 *   <li>{@code createdByName} (String, optional)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/customer/{customerId}/call-record")
@RequiredArgsConstructor
public class CallRecordController {

    private final CallRecordService service;

    /** GET — paginated list (newest first). */
    @GetMapping
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<CallRecord> result = service.list(factoryId, customerId, page, size);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "content", result.getContent(),
                        "totalElements", result.getTotalElements(),
                        "totalPages", result.getTotalPages(),
                        "count", result.getTotalElements()
                )
        ));
    }

    /** GET /{id} — 单记录 (frontend poll for transcript status). */
    @GetMapping("/{id}")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @PathVariable Long id
    ) {
        CallRecord r = service.get(factoryId, id);
        if (!customerId.equals(r.getCustomerId())) {
            throw new BusinessException(403, "记录不属于此客户");
        }
        return ResponseEntity.ok(Map.of("success", true, "data", r));
    }

    /**
     * POST — 创建通话记录 + 可选录音上传.
     *
     * <p>multipart/form-data. 防呆 R4 dedup: 5min 内同 (factoryId, customerId, callTime ±5min)
     * 二次创建 → 409 + actionHint (frontend ElMessageBox + router.push).
     */
    @PostMapping(consumes = "multipart/form-data")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @RequestParam("callType") String callTypeStr,
            @RequestParam(value = "audio", required = false) MultipartFile audioFile,
            @RequestParam(value = "callTime", required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime callTime,
            @RequestParam(value = "durationSec", required = false) Integer durationSec,
            @RequestParam(value = "contactName", required = false) String contactName,
            @RequestParam(value = "contactPhone", required = false) String contactPhone,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam("createdBy") Long createdBy,
            @RequestParam(value = "createdByName", required = false) String createdByName
    ) {
        CallType callType = parseCallType(callTypeStr);

        CallRecord saved = service.create(factoryId, customerId, callType,
                callTime, durationSec, contactName, contactPhone, remark,
                createdBy, createdByName, audioFile);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "通话记录已创建" + (saved.getAudioOssUrl() != null ? "(转写进行中)" : "")
        ));
    }

    /**
     * PUT /{id} — JSON body, partial update.
     * 不允许改 audioOssUrl / callType / callTime / factoryId / customerId / createdBy.
     */
    @PutMapping("/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCallRecordRequest body
    ) {
        CallRecord existing = service.get(factoryId, id);
        if (!customerId.equals(existing.getCustomerId())) {
            throw new BusinessException(403, "记录不属于此客户");
        }
        CallRecord saved = service.update(factoryId, id,
                body.getContactName(), body.getContactPhone(),
                body.getRemark(), body.getDurationSec());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "通话记录已更新"
        ));
    }

    /** DELETE /{id} — 软删除. */
    @DeleteMapping("/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @PathVariable Long id
    ) {
        CallRecord existing = service.get(factoryId, id);
        if (!customerId.equals(existing.getCustomerId())) {
            throw new BusinessException(403, "记录不属于此客户");
        }
        service.delete(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", id),
                "message", "通话记录已删除"
        ));
    }

    /** 解析 callType 字符串 → enum, 白名单校验 (R3 dropdown). */
    private CallType parseCallType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "callType 必填 (INCOMING/OUTGOING/MISSED)");
        }
        try {
            return CallType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400,
                    "callType 必须是 INCOMING/OUTGOING/MISSED 之一, 实际: " + value);
        }
    }

    /** PUT body — all fields optional (partial update). */
    @Data
    public static class UpdateCallRecordRequest {
        private String contactName;
        private String contactPhone;
        private String remark;
        private Integer durationSec;
    }

    /**
     * Legacy JSON POST body shape (for reference / tooling). Actual HTTP endpoint
     * uses multipart/form-data with @RequestParam — see {@link #create}.
     */
    @Data
    public static class CreateCallRecordRequest {
        @NotBlank
        private String callType;

        private LocalDateTime callTime;
        private Integer durationSec;
        private String contactName;
        private String contactPhone;
        private String remark;

        @NotNull
        private Long createdBy;
        private String createdByName;
    }
}
