package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.OpportunityStageHistory;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SalesOpportunityRepository;
import com.cretas.aims.service.SalesOpportunityService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售商机 REST controller — Sprint 7 wave 2 T4 (2026-05-20).
 *
 * <p>Base URL: {@code /api/mobile/{factoryId}/sales-opportunity}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET                            — paged list (filter by stage, ownerId)</li>
 *   <li>GET /{id}                      — single opportunity</li>
 *   <li>GET /{id}/history              — stage history</li>
 *   <li>GET /funnel-stats              — aggregated funnel by stage</li>
 *   <li>POST                           — create</li>
 *   <li>PUT /{id}                      — partial update (no stage)</li>
 *   <li>PUT /{id}/transition           — transition stage (state machine)</li>
 *   <li>DELETE /{id}                   — soft delete</li>
 * </ul>
 *
 * <p>RBAC: sales:view (read) / sales:edit (write).
 *
 * <p>Response 统一格式 (per .claude/rules/api-response-handling.md):
 * <pre>{ "success": true, "data": ..., "message": "..." }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/sales-opportunity")
@RequiredArgsConstructor
public class SalesOpportunityController {

    private final SalesOpportunityService service;

    @GetMapping
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        OpportunityStage stageEnum = parseStageOrNull(stage);
        Page<SalesOpportunity> result = service.list(factoryId, stageEnum, ownerId, page, size);
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
        SalesOpportunity opp = service.get(factoryId, id);
        return ResponseEntity.ok(Map.of("success", true, "data", opp));
    }

    @GetMapping("/{id}/history")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> history(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        List<OpportunityStageHistory> list = service.getHistory(factoryId, id);
        return ResponseEntity.ok(Map.of("success", true, "data", list));
    }

    /**
     * Funnel 统计 — 按 stage 汇总 count + sum(value_amount) + expected (× probability/100).
     * 用于 funnel chart + kanban column header.
     */
    @GetMapping("/funnel-stats")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> funnelStats(
            @PathVariable String factoryId,
            @RequestParam(required = false) Long ownerId
    ) {
        List<SalesOpportunityRepository.FunnelStat> raw = service.getFunnelStats(factoryId, ownerId);

        // 转换 projection 到普通 dict (frontend 友好)
        List<Map<String, Object>> data = new java.util.ArrayList<>();
        for (SalesOpportunityRepository.FunnelStat s : raw) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("stage", s.getStage() != null ? s.getStage().name() : null);
            entry.put("stageDisplay", s.getStage() != null ? s.getStage().getDisplayName() : null);
            entry.put("count", s.getCnt() != null ? s.getCnt() : 0L);
            entry.put("totalValue", s.getTotalValue() != null ? s.getTotalValue() : BigDecimal.ZERO);
            entry.put("expectedValue", s.getExpectedValue() != null ? s.getExpectedValue() : BigDecimal.ZERO);
            data.add(entry);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @PostMapping
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateOpportunityRequest body
    ) {
        OpportunityStage initialStage = parseStageOrNull(body.getStage());
        SalesOpportunity saved = service.create(
                factoryId,
                body.getCustomerId(),
                body.getTitle(),
                initialStage,
                body.getValueAmount(),
                body.getProbability(),
                body.getExpectedCloseDate(),
                body.getOwnerId(),
                body.getDescription(),
                body.getCreatedBy()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "商机已创建"
        ));
    }

    @PutMapping("/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody UpdateOpportunityRequest body
    ) {
        SalesOpportunity saved = service.update(
                factoryId, id,
                body.getTitle(),
                body.getValueAmount(),
                body.getProbability(),
                body.getExpectedCloseDate(),
                body.getOwnerId(),
                body.getDescription()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "商机已更新"
        ));
    }

    /**
     * 阶段转换 — state machine 主入口.
     *
     * <p>Body 字段:
     * <ul>
     *   <li>newStage (required): 目标 stage</li>
     *   <li>reason: 原因 (backward / skip / closed-reactivate 必填)</li>
     *   <li>confirmSkip: 跳级确认 (skip-forward 必传 true)</li>
     *   <li>probabilityOverride: 手动覆盖默认 probability</li>
     *   <li>changedBy: 操作人 User.id (必填)</li>
     * </ul>
     */
    @PutMapping("/{id}/transition")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> transition(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody TransitionStageRequest body
    ) {
        OpportunityStage newStage = parseStage(body.getNewStage());
        SalesOpportunity saved = service.transitionStage(
                factoryId, id, newStage,
                body.getReason(),
                Boolean.TRUE.equals(body.getConfirmSkip()),
                body.getProbabilityOverride(),
                body.getChangedBy()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "商机阶段已变更为 " + newStage.getDisplayName()
        ));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        service.delete(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", id),
                "message", "商机已删除"
        ));
    }

    // ==================== Helpers ====================

    private OpportunityStage parseStageOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OpportunityStage.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "stage 非法: " + value
                    + ". 合法值: LEAD/QUALIFIED/DEMO/PROPOSAL/NEGOTIATE/VERBAL/CLOSED_WON/CLOSED_LOST");
        }
    }

    private OpportunityStage parseStage(String value) {
        OpportunityStage stage = parseStageOrNull(value);
        if (stage == null) {
            throw new BusinessException(400, "stage 必填");
        }
        return stage;
    }

    // ==================== Request bodies ====================

    @Data
    public static class CreateOpportunityRequest {
        @jakarta.validation.constraints.NotBlank
        private String customerId;
        @jakarta.validation.constraints.NotBlank
        private String title;
        private String stage;  // optional, default LEAD
        private BigDecimal valueAmount;
        private Integer probability;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate expectedCloseDate;
        @jakarta.validation.constraints.NotNull
        private Long ownerId;
        private String description;
        @jakarta.validation.constraints.NotNull
        private Long createdBy;
    }

    @Data
    public static class UpdateOpportunityRequest {
        private String title;
        private BigDecimal valueAmount;
        private Integer probability;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate expectedCloseDate;
        private Long ownerId;
        private String description;
    }

    @Data
    public static class TransitionStageRequest {
        @jakarta.validation.constraints.NotBlank
        private String newStage;
        private String reason;
        private Boolean confirmSkip;
        private Integer probabilityOverride;
        @jakarta.validation.constraints.NotNull
        private Long changedBy;
    }
}
