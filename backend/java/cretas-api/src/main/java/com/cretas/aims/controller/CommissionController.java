package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.CommissionService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 提成 + 提成规则 REST controller — Sprint 7 wave 2 T5 (2026-05-20).
 *
 * <p>Base URL: {@code /api/mobile/{factoryId}/commission}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET                   — paged list (filter by status, salesId)</li>
 *   <li>GET /{id}             — single commission</li>
 *   <li>PUT /{id}/mark-paid   — 标记发放</li>
 *   <li>PUT /{id}/cancel      — 取消 (PENDING 状态)</li>
 *   <li>GET /rules            — list 规则</li>
 *   <li>POST /rules           — 创建规则</li>
 *   <li>PUT /rules/{id}       — 更新规则</li>
 *   <li>DELETE /rules/{id}    — 删除规则</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/commission")
@RequiredArgsConstructor
public class CommissionController {

    private final CommissionService service;

    @GetMapping
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long salesId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CommissionStatus statusEnum = parseStatusOrNull(status);
        Page<Commission> result = service.list(factoryId, statusEnum, salesId, page, size);
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

    @GetMapping("/{id}")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        Commission c = service.get(factoryId, id);
        return ResponseEntity.ok(Map.of("success", true, "data", c));
    }

    @PutMapping("/{id}/mark-paid")
    @RequirePermission("finance:edit")
    public ResponseEntity<Map<String, Object>> markPaid(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        Commission c = service.markPaid(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", c,
                "message", "提成已标记为已发放"
        ));
    }

    @PutMapping("/{id}/cancel")
    @RequirePermission("finance:edit")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        Commission c = service.cancel(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", c,
                "message", "提成已取消"
        ));
    }

    // ==================== Rule CRUD ====================

    @GetMapping("/rules")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> listRules(
            @PathVariable String factoryId
    ) {
        List<CommissionRule> rules = service.listRules(factoryId);
        return ResponseEntity.ok(Map.of("success", true, "data", rules));
    }

    @PostMapping("/rules")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> createRule(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateRuleRequest body
    ) {
        CommissionRule rule = service.createRule(
                factoryId, body.getSalesId(), body.getCustomerType(),
                body.getPercentage(), body.getEffectiveFrom(), body.getEffectiveTo(),
                body.getCreatedBy()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", rule,
                "message", "提成规则已创建"
        ));
    }

    @PutMapping("/rules/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> updateRule(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody UpdateRuleRequest body
    ) {
        CommissionRule rule = service.updateRule(
                factoryId, id, body.getPercentage(),
                body.getEffectiveFrom(), body.getEffectiveTo(), body.getActive()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", rule,
                "message", "提成规则已更新"
        ));
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> deleteRule(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        service.deleteRule(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", id),
                "message", "提成规则已删除"
        ));
    }

    // ==================== Helpers ====================

    private CommissionStatus parseStatusOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return CommissionStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "status 非法: " + value
                    + ". 合法值: PENDING/PAID/CANCELLED");
        }
    }

    // ==================== Request bodies ====================

    @Data
    public static class CreateRuleRequest {
        private Long salesId;       // nullable
        private String customerType;  // nullable
        @jakarta.validation.constraints.NotNull
        private BigDecimal percentage;
        @jakarta.validation.constraints.NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveTo;
        @jakarta.validation.constraints.NotNull
        private Long createdBy;
    }

    @Data
    public static class UpdateRuleRequest {
        private BigDecimal percentage;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveTo;
        private Boolean active;
    }
}
