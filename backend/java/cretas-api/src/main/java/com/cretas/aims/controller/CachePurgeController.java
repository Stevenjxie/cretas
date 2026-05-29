package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.cache.CachePurgeEvent;
import com.cretas.aims.service.cache.CachePurgeScope;
import com.cretas.aims.service.cache.CachePurgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin REST endpoint for manual cache invalidation (Sprint 12).
 *
 * <p>Endpoint: {@code POST /api/admin/cache/purge?scope=ROUTING|INDICATOR|ALL&factoryId=...&intentCode=...&reason=...}
 *
 * <p>Use cases:
 * <ul>
 *   <li>After a routing fix deploy when stale cache rows would otherwise serve old responses
 *     (per {@code feedback_stale_cache_poisoning_survives_backend_fix} HARD rule).</li>
 *   <li>Sister chat (BI indicator-service-rewrite, 餐饮 backend) — call after batch indicator
 *     definition updates / ETL backfills that change cached values.</li>
 *   <li>Emergency: customer reports stale data, on-call wants immediate invalidation.</li>
 * </ul>
 *
 * <p>For service-code paths, prefer publishing {@link CachePurgeEvent} via
 * {@code ApplicationEventPublisher} so purge is transactional-safe. This endpoint is the
 * manual / on-call escape hatch.
 *
 * <p>Authorization: requires platform_admin / super_admin / developer role. The endpoint is
 * idempotent — repeated calls are safe (no rows = no-op).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
@Tag(name = "缓存管理 (Sprint 12)", description = "管理员缓存清理 API — semantic + tool_call cache 失效")
public class CachePurgeController {

    private final CachePurgeService cachePurgeService;

    @PostMapping("/purge")
    @Operation(summary = "手动触发缓存清理",
            description = "按 scope (ROUTING / INDICATOR / ALL) 清理 semantic_cache + tool_call_cache. " +
                          "scope=ROUTING 需 factoryId, intentCode 可选; scope=INDICATOR 需 factoryId, " +
                          "indicatorCode 仅用于日志; scope=ALL 需 factoryId. 仅平台管理员可调用.")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<Map<String, Object>> purge(
            @Parameter(description = "Purge 范围: ROUTING / INDICATOR / ALL", required = true)
            @RequestParam CachePurgeScope scope,
            @Parameter(description = "目标工厂 ID (必填)", required = true)
            @RequestParam String factoryId,
            @Parameter(description = "Intent code (ROUTING) 或 indicator code (INDICATOR); 可选")
            @RequestParam(required = false) String targetCode,
            @Parameter(description = "Purge 原因 (审计日志用)")
            @RequestParam(defaultValue = "manual-admin-purge") String reason) {

        log.info("[CachePurgeController] manual purge requested: scope={} factoryId={} targetCode={} reason={}",
                scope, factoryId, targetCode, reason);

        CachePurgeEvent event = new CachePurgeEvent(scope, factoryId, targetCode, reason,
                java.time.LocalDateTime.now());
        int deleted = cachePurgeService.purge(event);

        Map<String, Object> body = new HashMap<>();
        body.put("scope", scope.name());
        body.put("factoryId", factoryId);
        body.put("targetCode", targetCode);
        body.put("reason", reason);
        body.put("rowsDeleted", deleted);
        body.put("executedAt", event.publishedAt().toString());
        return ApiResponse.success(deleted == 0 ? "无匹配缓存可清理 (返回 0 安全)" : "缓存清理完成", body);
    }
}
