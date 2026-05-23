package com.cretas.aims.service.indicator.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标计算结果 DTO — 携带值 + 计算时刻 + 缓存命中标识 + 来源标签.
 *
 * <p>{@code source} 取值约定:
 * <ul>
 *   <li>{@code "cache"} — Cached strategy, TTL 内命中</li>
 *   <li>{@code "python"} — Cached strategy 过期 / Realtime strategy fetch Python</li>
 *   <li>{@code "precomputed"} — Precomputed strategy 直接返 lastValue</li>
 * </ul>
 *
 * <p>Record class (Java 21), 不可变。Phase 2 可扩展 alertLevel / thresholds 字段。
 *
 * @param value       指标取值
 * @param computedAt  物理计算时刻 (cache hit 时为上次 fetch 时刻)
 * @param cacheHit    {@code true} 表示从内存/数据库缓存返；{@code false} 表示新算
 * @param source      来源标签 (cache / python / precomputed)
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 */
public record IndicatorValueResult(
        BigDecimal value,
        LocalDateTime computedAt,
        boolean cacheHit,
        String source
) {}
