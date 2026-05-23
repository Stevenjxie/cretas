package com.cretas.aims.service.indicator.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标计算结果 — minimum-viable shape required by {@code IndicatorController}.
 *
 * <p>Stub introduced 2026-05-22 to unblock {@code main} compilation after PR #200
 * cherry-picked {@code IndicatorController} + DTOs without the Day 4 service layer
 * (per PR #200 commit message: "UI 依赖未 merge"). Full computation lives in the
 * sister chat's Day 4 deliverable (spec
 * {@code docs/superpowers/dispatch/2026-05-22-phase1-day4-indicator-query-service-dispatch.md});
 * once that lands this stub will be superseded by the real
 * {@code IndicatorValueResult} type.
 *
 * @param value       indicator's computed value
 * @param computedAt  the wall-clock moment the value was produced
 * @param cacheHit    {@code true} if served from cache, {@code false} on fresh compute
 * @param source      provenance tag: {@code "cache" | "python" | "precomputed"}
 */
public record IndicatorValueResult(
        BigDecimal value,
        LocalDateTime computedAt,
        Boolean cacheHit,
        String source
) {
}
