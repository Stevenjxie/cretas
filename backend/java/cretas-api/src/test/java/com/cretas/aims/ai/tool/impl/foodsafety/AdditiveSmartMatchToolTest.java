package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdditiveSmartMatchTool} (Sprint 9 P2.F V2). */
@ExtendWith(MockitoExtension.class)
class AdditiveSmartMatchToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AdditiveSmartMatchTool tool;

    @Mock
    private AdditiveLimitRepository additiveLimitRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ASM-01: metadata")
    void metadata() {
        assertEquals("additive_smart_match", tool.getToolName());
    }

    @Test
    @DisplayName("UT-ASM-02: EXACT_CODE match — INS 250 → 亚硝酸钠")
    @SuppressWarnings("unchecked")
    void exactCodeMatch() throws Exception {
        AdditiveLimit nitrite = buildLimit("亚硝酸钠", "INS 250", "08.02 熟肉制品",
                new BigDecimal("30"), List.of("亚硝酸钠", "Sodium Nitrite"));
        when(additiveLimitRepository.findByAdditiveCodeAndActiveTrue("INS 250"))
                .thenReturn(List.of(nitrite));

        Map<String, Object> params = Map.of("additiveQuery", "INS 250");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("EXACT_CODE", data.get("matchStrategy"));
        assertEquals(1, data.get("matchCount"));
        List<Map<String, Object>> matches = (List<Map<String, Object>>) data.get("matches");
        assertEquals("亚硝酸钠", matches.get(0).get("additiveName"));
    }

    @Test
    @DisplayName("UT-ASM-03: EXACT_NAME match — 亚硝酸钠 → INS 250")
    @SuppressWarnings("unchecked")
    void exactNameMatch() throws Exception {
        AdditiveLimit nitrite = buildLimit("亚硝酸钠", "INS 250", "08.02 熟肉制品",
                new BigDecimal("30"), List.of("亚硝酸钠", "Sodium Nitrite"));
        when(additiveLimitRepository.findByAdditiveCodeAndActiveTrue("亚硝酸钠"))
                .thenReturn(List.of());
        when(additiveLimitRepository.findByAdditiveNameAndActiveTrue("亚硝酸钠"))
                .thenReturn(List.of(nitrite));

        Map<String, Object> params = Map.of("additiveQuery", "亚硝酸钠");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("EXACT_NAME", data.get("matchStrategy"));
        assertEquals(1, data.get("matchCount"));
    }

    @Test
    @DisplayName("UT-ASM-04: empty query → friendly empty match")
    @SuppressWarnings("unchecked")
    void emptyQuery() throws Exception {
        Map<String, Object> params = Map.of("additiveQuery", "  ");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("EMPTY_QUERY", data.get("matchStrategy"));
    }

    @Test
    @DisplayName("UT-ASM-05: cross-category aggregation — 苯甲酸钠 多类目聚合")
    @SuppressWarnings("unchecked")
    void crossCategoryAggregation() throws Exception {
        AdditiveLimit benzoateInMeat = buildLimit("苯甲酸钠", "INS 211", "08.02 熟肉制品",
                new BigDecimal("1000"), List.of("苯甲酸钠"));
        AdditiveLimit benzoateInSoy = buildLimit("苯甲酸钠", "INS 211", "12.04 酱油",
                new BigDecimal("1000"), List.of("苯甲酸钠"));
        AdditiveLimit benzoateInCarbo = buildLimit("苯甲酸钠", "INS 211", "14.04 碳酸饮料",
                new BigDecimal("200"), List.of("苯甲酸钠"));
        when(additiveLimitRepository.findByAdditiveCodeAndActiveTrue("INS 211"))
                .thenReturn(List.of(benzoateInMeat, benzoateInSoy, benzoateInCarbo));

        Map<String, Object> params = Map.of("additiveQuery", "INS 211");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("matchCount"));  // 1 个 additive 跨 3 类目聚合
        List<Map<String, Object>> matches = (List<Map<String, Object>>) data.get("matches");
        List<String> categories = (List<String>) matches.get(0).get("foodCategories");
        assertEquals(3, categories.size());
        List<Map<String, Object>> details = (List<Map<String, Object>>) matches.get(0).get("limitDetails");
        assertEquals(3, details.size());
    }

    @Test
    @DisplayName("UT-ASM-06: no match → all strategies fail → NO_MATCH")
    @SuppressWarnings("unchecked")
    void noMatch() throws Exception {
        when(additiveLimitRepository.findByAdditiveCodeAndActiveTrue(anyString()))
                .thenReturn(List.of());
        when(additiveLimitRepository.findByAdditiveNameAndActiveTrue(anyString()))
                .thenReturn(List.of());
        when(additiveLimitRepository.findByAliasContaining(anyString()))
                .thenReturn(List.of());
        when(additiveLimitRepository.findByAdditiveNameContainingAndActiveTrue(anyString()))
                .thenReturn(List.of());
        when(additiveLimitRepository.findAllActiveForFuzzy())
                .thenReturn(List.of());

        Map<String, Object> params = Map.of("additiveQuery", "不存在的添加剂XYZ123");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("NO_MATCH", data.get("matchStrategy"));
        assertEquals(0, data.get("matchCount"));
    }

    // ── factoryId 隔离豁免验证 (EXEMPT: 全局参考数据 GB 2760-2014) ──────────

    @Test
    @DisplayName("UT-ASM-ISO-01: EXEMPT — global GB 2760 data, same result for any factoryId")
    @SuppressWarnings("unchecked")
    void exemptGlobalDataSameForAllFactories() throws Exception {
        AdditiveLimit sodium = buildLimit("亚硝酸钠", "INS 250", "腌制肉品", new BigDecimal("150"), null);
        when(additiveLimitRepository.findByAdditiveCodeAndActiveTrue("INS 250"))
                .thenReturn(List.of(sodium));

        Map<String, Object> params = Map.of("additiveQuery", "INS 250");

        // Factory A result
        Map<String, Object> resultA = invoke("doExecute", "F001", params, ctxFor("F001"));
        // Factory B result — same global GB 2760 data
        Map<String, Object> resultB = invoke("doExecute", "F999", params, ctxFor("F999"));

        Map<String, Object> dataA = (Map<String, Object>) resultA.get("data");
        Map<String, Object> dataB = (Map<String, Object>) resultB.get("data");
        assertEquals(dataA.get("matchCount"), dataB.get("matchCount"),
                "GB 2760-2014 national standard data must return same result for all factories (EXEMPT)");
        assertEquals(1, dataA.get("matchCount"));
        assertEquals("EXACT_CODE", dataA.get("matchStrategy"));
    }

    // ── helpers ──
    private Map<String, Object> ctxFor(String factoryId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        ctx.put("userId", 1L);
        return ctx;
    }

    private AdditiveLimit buildLimit(String name, String code, String category,
                                     BigDecimal maxLimit, List<String> aliases) {
        return AdditiveLimit.builder()
                .additiveCode(code)
                .additiveName(name)
                .foodCategory(category)
                .maxLimit(maxLimit)
                .unit("mg/kg")
                .regulationRef("GB 2760-2014 表 A.1")
                .aliases(aliases != null ? new java.util.ArrayList<>(aliases) : null)
                .build();
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
