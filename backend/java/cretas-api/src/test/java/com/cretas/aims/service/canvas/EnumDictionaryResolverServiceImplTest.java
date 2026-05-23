package com.cretas.aims.service.canvas;

import com.cretas.aims.entity.canvas.EnumDictionary;
import com.cretas.aims.repository.canvas.EnumDictionaryRepository;
import com.cretas.aims.service.canvas.impl.EnumDictionaryResolverServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * EnumDictionaryResolverServiceImpl 单元测试 — Canvas-Phase C.
 *
 * Coverage:
 * <ol>
 *   <li>per-factory enabled hit → returns per-factory rows</li>
 *   <li>per-factory empty → fallback global "*" rows</li>
 *   <li>per-factory has only disabled → fallback global</li>
 *   <li>cache hit: 2nd call does not hit repository</li>
 *   <li>invalidate(factoryId, category) flushes single entry</li>
 *   <li>invalidateAll() flushes all</li>
 *   <li>blank category → empty list</li>
 * </ol>
 */
@DisplayName("EnumDictionaryResolverServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class EnumDictionaryResolverServiceImplTest {

    @Mock
    private EnumDictionaryRepository repository;

    @InjectMocks
    private EnumDictionaryResolverServiceImpl resolver;

    @BeforeEach
    void clearCache() {
        resolver.invalidateAll();
    }

    private static EnumDictionary row(String factoryId, String category, String code,
                                      String label, boolean enabled) {
        EnumDictionary e = new EnumDictionary();
        e.setId(UUID.randomUUID());
        e.setFactoryId(factoryId);
        e.setCategory(category);
        e.setCode(code);
        e.setLabel(label);
        e.setEnabled(enabled);
        e.setDisplayOrder(0);
        e.setLocale(EnumDictionary.DEFAULT_LOCALE);
        e.setVersion(0L);
        return e;
    }

    @Test
    @DisplayName("per-factory enabled rows → 返回工厂级配置")
    void perFactoryHit() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(List.of(
                        row("F001", "CANCEL_REASON", "CUSTOMER_CANCEL", "客户撤单", true),
                        row("F001", "CANCEL_REASON", "QUALITY_ISSUE", "质量问题", true)));

        List<EnumDictionary> result = resolver.getEnumValues("F001", "CANCEL_REASON");

        assertEquals(2, result.size());
        assertEquals("F001", result.get(0).getFactoryId());
        // should NOT lookup global since per-factory has enabled rows
        verify(repository, never()).findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "*", "CANCEL_REASON");
    }

    @Test
    @DisplayName("per-factory 空 → fallback 到 global '*'")
    void perFactoryEmptyFallbackGlobal() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(Collections.emptyList());
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "*", "CANCEL_REASON"))
                .thenReturn(List.of(
                        row("*", "CANCEL_REASON", "OTHER", "其他", true)));

        List<EnumDictionary> result = resolver.getEnumValues("F001", "CANCEL_REASON");

        assertEquals(1, result.size());
        assertEquals("*", result.get(0).getFactoryId());
    }

    @Test
    @DisplayName("per-factory 全部 disabled → fallback global")
    void perFactoryAllDisabledFallback() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(List.of(
                        row("F001", "CANCEL_REASON", "X", "x", false))); // disabled
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "*", "CANCEL_REASON"))
                .thenReturn(List.of(
                        row("*", "CANCEL_REASON", "OTHER", "其他", true)));

        List<EnumDictionary> result = resolver.getEnumValues("F001", "CANCEL_REASON");

        assertEquals(1, result.size());
        assertEquals("*", result.get(0).getFactoryId());
    }

    @Test
    @DisplayName("disabled 行被过滤掉")
    void disabledRowsFiltered() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(List.of(
                        row("F001", "CANCEL_REASON", "A", "a", true),
                        row("F001", "CANCEL_REASON", "B", "b", false), // 过滤
                        row("F001", "CANCEL_REASON", "C", "c", true)));

        List<EnumDictionary> result = resolver.getEnumValues("F001", "CANCEL_REASON");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(r -> Boolean.TRUE.equals(r.getEnabled())));
    }

    @Test
    @DisplayName("Cache hit — 2nd call 不再查 DB")
    void cacheHit() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(List.of(row("F001", "CANCEL_REASON", "A", "a", true)));

        resolver.getEnumValues("F001", "CANCEL_REASON");
        resolver.getEnumValues("F001", "CANCEL_REASON");
        resolver.getEnumValues("F001", "CANCEL_REASON");

        // 仅一次 DB 调用 (其余从 cache)
        verify(repository, times(1))
                .findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc("F001", "CANCEL_REASON");
    }

    @Test
    @DisplayName("invalidate(factoryId, category) 后再查 DB 重新加载")
    void invalidateSingleEntry() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(List.of(row("F001", "CANCEL_REASON", "A", "a", true)));

        resolver.getEnumValues("F001", "CANCEL_REASON");
        resolver.invalidate("F001", "CANCEL_REASON");
        resolver.getEnumValues("F001", "CANCEL_REASON");

        verify(repository, times(2))
                .findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc("F001", "CANCEL_REASON");
    }

    @Test
    @DisplayName("blank/null category → 空 list")
    void blankCategory() {
        assertTrue(resolver.getEnumValues("F001", "").isEmpty());
        assertTrue(resolver.getEnumValues("F001", "   ").isEmpty());
        assertTrue(resolver.getEnumValues("F001", null).isEmpty());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("null/blank factoryId → 直接查 global '*'")
    void nullFactoryIdGoesGlobal() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "*", "CANCEL_REASON"))
                .thenReturn(List.of(row("*", "CANCEL_REASON", "X", "x", true)));

        List<EnumDictionary> result = resolver.getEnumValues(null, "CANCEL_REASON");
        assertEquals(1, result.size());
        assertEquals("*", result.get(0).getFactoryId());

        List<EnumDictionary> result2 = resolver.getEnumValues("", "CANCEL_REASON");
        assertEquals(1, result2.size());
    }

    @Test
    @DisplayName("invalidateAll() 清空全部")
    void invalidateAll() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                "F001", "CANCEL_REASON"))
                .thenReturn(List.of(row("F001", "CANCEL_REASON", "A", "a", true)));

        resolver.getEnumValues("F001", "CANCEL_REASON");
        resolver.invalidateAll();
        resolver.getEnumValues("F001", "CANCEL_REASON");

        verify(repository, times(2))
                .findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc("F001", "CANCEL_REASON");
    }
}
