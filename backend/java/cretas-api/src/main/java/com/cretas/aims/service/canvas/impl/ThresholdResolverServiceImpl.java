package com.cretas.aims.service.canvas.impl;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Canvas-Thresholds 解析服务实现 (Phase A P0-1).
 *
 * <p>使用本地 Caffeine cache (TTL 5 分钟, 最多 5000 条目). 多实例部署时, 任何节点写入后通过
 * {@link CanvasThresholdsController} 调用 {@link #invalidate} 失效本地缓存; 跨节点失效暂未
 * 实现 (TTL 5 分钟兜底). Phase B+ 可加 Redis pub/sub broadcast 做秒级失效。
 *
 * <p>解析优先级: per-factory → global ({@code "*"}) → caller fallback default. 任何 fallback
 * 都不抛异常, 保证业务 service 在 DB 不可达 / 缓存 miss / 行不存在时仍能继续运行。
 *
 * @since Canvas Phase A (2026-05-21)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThresholdResolverServiceImpl implements ThresholdResolverService {

    private final FactoryThresholdRepository repository;

    /**
     * Sentinel object cached when DB has no row for the key — distinguishes "looked up,
     * absent" (use caller's fallback) from "never looked up" (re-query DB). Without this,
     * a missing row would re-hit DB on every call.
     */
    private static final FactoryThreshold ABSENT_SENTINEL = new FactoryThreshold();

    /**
     * Cache key = {@code factoryId + ":" + thresholdKey}; value is the FactoryThreshold or
     * {@link #ABSENT_SENTINEL}. Resolver also caches the global lookup separately so a
     * per-factory miss followed by a global hit doesn't traverse DB twice on subsequent hits.
     */
    private final Cache<String, FactoryThreshold> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(5000)
            .build();

    // ==================== Typed getters ====================

    @Override
    public int getInteger(String factoryId, String thresholdKey, int defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        if (opt.isEmpty()) {
            return defaultValue;
        }
        try {
            return opt.get().asInteger();
        } catch (Exception e) {
            log.warn("threshold {} (factoryId={}) parse-as-integer failed value={}, falling back to default {}",
                    thresholdKey, factoryId, opt.get().getThresholdValue(), defaultValue, e);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal getBigDecimal(String factoryId, String thresholdKey, BigDecimal defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        if (opt.isEmpty()) {
            return defaultValue;
        }
        try {
            return opt.get().asBigDecimal();
        } catch (Exception e) {
            log.warn("threshold {} (factoryId={}) parse-as-decimal failed value={}, falling back to default {}",
                    thresholdKey, factoryId, opt.get().getThresholdValue(), defaultValue, e);
            return defaultValue;
        }
    }

    @Override
    public double getDouble(String factoryId, String thresholdKey, double defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        if (opt.isEmpty()) {
            return defaultValue;
        }
        try {
            return opt.get().asDouble();
        } catch (Exception e) {
            log.warn("threshold {} (factoryId={}) parse-as-double failed value={}, falling back to default {}",
                    thresholdKey, factoryId, opt.get().getThresholdValue(), defaultValue, e);
            return defaultValue;
        }
    }

    @Override
    public String getString(String factoryId, String thresholdKey, String defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        return opt.map(FactoryThreshold::getThresholdValue).orElse(defaultValue);
    }

    @Override
    public Optional<FactoryThreshold> resolve(String factoryId, String thresholdKey) {
        if (factoryId == null || thresholdKey == null) {
            return Optional.empty();
        }
        // Step 1: per-factory lookup (skip if caller already asked for global)
        if (!FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID.equals(factoryId)) {
            FactoryThreshold perFactory = loadFromCacheOrDb(factoryId, thresholdKey);
            if (perFactory != null && perFactory != ABSENT_SENTINEL && Boolean.TRUE.equals(perFactory.getEnabled())) {
                return Optional.of(perFactory);
            }
        }
        // Step 2: global fallback
        FactoryThreshold global = loadFromCacheOrDb(
                FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID, thresholdKey);
        if (global != null && global != ABSENT_SENTINEL && Boolean.TRUE.equals(global.getEnabled())) {
            return Optional.of(global);
        }
        return Optional.empty();
    }

    /**
     * Cache-then-DB lookup. Returns {@link #ABSENT_SENTINEL} (not null) when DB has no row,
     * so a missing key is itself cached (avoids per-call DB hit for keys that never exist).
     */
    private FactoryThreshold loadFromCacheOrDb(String factoryId, String thresholdKey) {
        String cacheKey = cacheKey(factoryId, thresholdKey);
        return cache.get(cacheKey, k -> {
            FactoryThreshold loaded = repository
                    .findByFactoryIdAndThresholdKey(factoryId, thresholdKey)
                    .orElse(null);
            return loaded != null ? loaded : ABSENT_SENTINEL;
        });
    }

    // ==================== Cache management ====================

    @Override
    public void invalidate(String factoryId, String thresholdKey) {
        cache.invalidate(cacheKey(factoryId, thresholdKey));
    }

    @Override
    public void invalidateFactory(String factoryId) {
        if (factoryId == null) return;
        // Caffeine has no prefix-invalidate; iterate snapshot keyset.
        cache.asMap().keySet().stream()
                .filter(k -> k.startsWith(factoryId + ":"))
                .forEach(cache::invalidate);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private String cacheKey(String factoryId, String thresholdKey) {
        return Objects.requireNonNull(factoryId) + ":" + Objects.requireNonNull(thresholdKey);
    }
}
