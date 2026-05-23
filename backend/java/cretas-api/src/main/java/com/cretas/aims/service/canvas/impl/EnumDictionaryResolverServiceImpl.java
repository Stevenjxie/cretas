package com.cretas.aims.service.canvas.impl;

import com.cretas.aims.entity.canvas.EnumDictionary;
import com.cretas.aims.repository.canvas.EnumDictionaryRepository;
import com.cretas.aims.service.canvas.EnumDictionaryResolverService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Canvas-Phase C 枚举字典解析服务实现.
 *
 * <p>使用本地 Caffeine cache (TTL 5 分钟, 最多 1000 条 (factoryId, category)). 多实例部署时,
 * 通过 {@link com.cretas.aims.controller.CanvasEnumDictionaryController} 在写入后调用
 * {@link #invalidate} 失效本地缓存; 跨节点失效暂未实现 (TTL 5 分钟兜底).
 *
 * <p>解析优先级: per-factory enabled rows → fallback global ("*"). 一旦 per-factory 有任何
 * enabled row, 视为该工厂已自定义, 不再 fallback 全局.
 *
 * @since Canvas Phase C (2026-05-22)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnumDictionaryResolverServiceImpl implements EnumDictionaryResolverService {

    private final EnumDictionaryRepository repository;

    /**
     * Cache key = {@code factoryId + ":" + category}; value is the ordered enum value list.
     */
    private final Cache<String, List<EnumDictionary>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();

    @Override
    public List<EnumDictionary> getEnumValues(String factoryId, String category) {
        if (category == null || category.isBlank()) {
            return Collections.emptyList();
        }
        String resolvedFactoryId = (factoryId == null || factoryId.isBlank())
                ? EnumDictionary.GLOBAL_FALLBACK_FACTORY_ID
                : factoryId;

        // Per-factory lookup first
        if (!EnumDictionary.GLOBAL_FALLBACK_FACTORY_ID.equals(resolvedFactoryId)) {
            List<EnumDictionary> perFactory = loadFromCacheOrDb(resolvedFactoryId, category);
            // 任何 enabled row 即视为工厂已自定义, 不 fallback
            boolean hasAnyEnabled = perFactory.stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getEnabled()));
            if (hasAnyEnabled) {
                return perFactory.stream().filter(r -> Boolean.TRUE.equals(r.getEnabled())).toList();
            }
        }

        // Fallback to global
        List<EnumDictionary> global = loadFromCacheOrDb(
                EnumDictionary.GLOBAL_FALLBACK_FACTORY_ID, category);
        return global.stream().filter(r -> Boolean.TRUE.equals(r.getEnabled())).toList();
    }

    private List<EnumDictionary> loadFromCacheOrDb(String factoryId, String category) {
        String cacheKey = cacheKey(factoryId, category);
        return cache.get(cacheKey,
                k -> repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(factoryId, category));
    }

    @Override
    public void invalidate(String factoryId, String category) {
        if (factoryId == null || category == null) return;
        cache.invalidate(cacheKey(factoryId, category));
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private String cacheKey(String factoryId, String category) {
        return Objects.requireNonNull(factoryId) + ":" + Objects.requireNonNull(category);
    }
}
