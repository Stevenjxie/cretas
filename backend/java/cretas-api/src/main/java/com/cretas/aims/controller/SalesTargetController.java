package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.SalesTarget;
import com.cretas.aims.entity.enums.TargetPeriod;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.SalesTargetService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 销售业绩目标 REST controller — Sprint 7 wave 2 T5 (2026-05-20).
 *
 * <p>Base URL: {@code /api/mobile/{factoryId}/sales-target}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST                 — 设置/更新目标 (upsert)</li>
 *   <li>DELETE /{id}         — 软删除目标</li>
 *   <li>GET /my              — 当前用户所有目标</li>
 *   <li>GET /progress        — 进度 (target/actual/completionRate/rank)</li>
 *   <li>GET /leaderboard     — 排行榜 (org-wide)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/sales-target")
@RequiredArgsConstructor
public class SalesTargetController {

    private final SalesTargetService service;

    @PostMapping
    @RequirePermission("sales:edit")
    public ResponseEntity<Map<String, Object>> setTarget(
            @PathVariable String factoryId,
            @Valid @RequestBody SetTargetRequest body
    ) {
        TargetPeriod period = parsePeriod(body.getPeriod());
        SalesTarget saved = service.setTarget(
                factoryId, body.getOwnerId(), period,
                body.getYear(), body.getPeriodNum(),
                body.getTargetAmount(), body.getCreatedBy()
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "目标已保存"
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
                "message", "目标已删除"
        ));
    }

    @GetMapping("/my")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> myTargets(
            @PathVariable String factoryId,
            @RequestParam Long ownerId
    ) {
        List<SalesTarget> targets = service.listMyTargets(factoryId, ownerId);
        return ResponseEntity.ok(Map.of("success", true, "data", targets));
    }

    @GetMapping("/progress")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> progress(
            @PathVariable String factoryId,
            @RequestParam Long ownerId,
            @RequestParam String period,
            @RequestParam Integer year,
            @RequestParam Integer periodNum
    ) {
        TargetPeriod p = parsePeriod(period);
        Map<String, Object> result = service.getProgress(factoryId, ownerId, p, year, periodNum);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @GetMapping("/leaderboard")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> leaderboard(
            @PathVariable String factoryId,
            @RequestParam String period,
            @RequestParam Integer year,
            @RequestParam Integer periodNum,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        TargetPeriod p = parsePeriod(period);
        Map<String, Object> result = service.getLeaderboard(factoryId, p, year, periodNum, limit);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    private TargetPeriod parsePeriod(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "period 必填 (MONTH/QUARTER/YEAR)");
        }
        try {
            return TargetPeriod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "period 非法: " + value + ". 合法值: MONTH/QUARTER/YEAR");
        }
    }

    @Data
    public static class SetTargetRequest {
        @jakarta.validation.constraints.NotNull
        private Long ownerId;
        @jakarta.validation.constraints.NotBlank
        private String period;
        @jakarta.validation.constraints.NotNull
        private Integer year;
        @jakarta.validation.constraints.NotNull
        private Integer periodNum;
        @jakarta.validation.constraints.NotNull
        private BigDecimal targetAmount;
        @jakarta.validation.constraints.NotNull
        private Long createdBy;
    }
}
