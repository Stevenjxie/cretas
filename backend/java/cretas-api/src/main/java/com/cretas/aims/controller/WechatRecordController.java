package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.entity.enums.WechatDirection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.WechatRecordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 微信记录 REST controller (Sprint 6 W2-C-1, 2026-05-19).
 *
 * <p>Base URL: {@code /api/mobile/{factoryId}/customer/{customerId}/wechat-record}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST   — create new wechat record (R4 dedup 5min)</li>
 *   <li>GET    — paginated list by customer (newest first)</li>
 *   <li>GET /{id}    — single record</li>
 *   <li>PUT /{id}    — partial update</li>
 *   <li>DELETE /{id} — soft delete</li>
 * </ul>
 *
 * <p>RBAC: sales:view (read) / sales:edit (write) — mirror CustomerTrackingRecordController.
 *
 * <p>Response 统一格式 (per .claude/rules/api-response-handling.md):
 * <pre>{ "success": true, "data": ..., "message": "..." }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/customer/{customerId}/wechat-record")
@RequiredArgsConstructor
public class WechatRecordController {

    private final WechatRecordService service;

    /**
     * GET — paginated list by customer (newest first).
     *
     * <p>Response data 包含 {@code count} 字段, 用于 dialog header 显
     * "已有 N 条" (防呆 R2 context, 参考 fool-proof-design.md).
     */
    @GetMapping
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<WechatRecord> result = service.list(factoryId, customerId, page, size);
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

    /** GET /{id} — 单记录详情. */
    @GetMapping("/{id}")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @PathVariable Long id
    ) {
        WechatRecord r = service.get(factoryId, id);
        if (!customerId.equals(r.getCustomerId())) {
            throw new BusinessException(403, "记录不属于此客户");
        }
        return ResponseEntity.ok(Map.of("success", true, "data", r));
    }

    /**
     * POST — 创建.
     *
     * <p>防呆 R4 dedup: 5min 内同 (factoryId, customerId, messageContent) 二次创建
     * → BusinessException 409 + actionHint (GlobalExceptionHandler 自动 HTTP 409).
     */
    @PostMapping
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @Valid @RequestBody CreateWechatRecordRequest body
    ) {
        // direction 白名单校验 (Service 层会再校验一次, 这里早 fail 给 UI 更好提示)
        WechatDirection direction = parseDirection(body.getDirection());

        WechatRecord saved = service.create(
                factoryId,
                customerId,
                direction,
                body.getMessageContent(),
                body.getCreatedBy(),
                body.getCreatedByName(),
                body.getRecordTime(),
                body.getContactPerson(),
                body.getRemark()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "微信记录已创建"
        ));
    }

    /** PUT /{id} — 部分更新 (direction / messageContent / contactPerson / remark). */
    @PutMapping("/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String factoryId,
            @PathVariable String customerId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateWechatRecordRequest body
    ) {
        // 先校验 record 归属此 customer (defense-in-depth)
        WechatRecord existing = service.get(factoryId, id);
        if (!customerId.equals(existing.getCustomerId())) {
            throw new BusinessException(403, "记录不属于此客户");
        }

        WechatDirection direction = body.getDirection() != null
                ? parseDirection(body.getDirection())
                : null;

        WechatRecord saved = service.update(
                factoryId,
                id,
                direction,
                body.getMessageContent(),
                body.getContactPerson(),
                body.getRemark()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "微信记录已更新"
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
        // 先校验归属
        WechatRecord existing = service.get(factoryId, id);
        if (!customerId.equals(existing.getCustomerId())) {
            throw new BusinessException(403, "记录不属于此客户");
        }
        service.delete(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", id),
                "message", "微信记录已删除"
        ));
    }

    /** 解析 direction 字符串 → enum, 白名单校验. */
    private WechatDirection parseDirection(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "direction 必填 (INBOUND/OUTBOUND/INTERNAL)");
        }
        try {
            return WechatDirection.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400,
                    "direction 必须是 INBOUND/OUTBOUND/INTERNAL 之一, 实际: " + value);
        }
    }

    /** POST body. */
    @Data
    public static class CreateWechatRecordRequest {
        /** R3 dropdown: INBOUND / OUTBOUND / INTERNAL */
        @NotBlank
        private String direction;

        @NotBlank
        private String messageContent;

        @NotNull
        private Long createdBy;

        private String createdByName;

        /** 可选, 默认当前时间. */
        private LocalDateTime recordTime;

        private String contactPerson;
        private String remark;
    }

    /** PUT body — all fields optional (partial update). */
    @Data
    public static class UpdateWechatRecordRequest {
        /** R3 dropdown: INBOUND / OUTBOUND / INTERNAL */
        private String direction;
        private String messageContent;
        private String contactPerson;
        private String remark;
    }
}
