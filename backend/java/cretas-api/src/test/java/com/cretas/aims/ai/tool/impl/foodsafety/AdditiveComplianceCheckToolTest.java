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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdditiveComplianceCheckTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class AdditiveComplianceCheckToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260518-A03";
    private static final String CATEGORY = "08.02 熟肉制品";

    @InjectMocks
    private AdditiveComplianceCheckTool tool;

    @Mock
    private AdditiveLimitRepository additiveLimitRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ACC-01: metadata")
    void metadata() {
        assertEquals("additive_compliance_check", tool.getToolName());
    }

    @Test
    @DisplayName("UT-ACC-02: VALIDATION mode — usage > limit → EXCEEDED detected")
    @SuppressWarnings("unchecked")
    void exceededDetected() throws Exception {
        AdditiveLimit nitrite = AdditiveLimit.builder()
                .additiveCode("INS 250").additiveName("亚硝酸钠")
                .foodCategory(CATEGORY)
                .maxLimit(new BigDecimal("30"))  // 30 mg/kg
                .unit("mg/kg").regulationRef("GB 2760-2014 表 A.1")
                .build();
        when(additiveLimitRepository.findByAdditiveCodeAndFoodCategoryAndActiveTrue(
                "INS 250", CATEGORY)).thenReturn(Optional.of(nitrite));

        Map<String, Object> ing = Map.of(
                "additiveCode", "INS 250",
                "usageAmount", new BigDecimal("50"),  // 超 30
                "unit", "mg/kg");
        Map<String, Object> params = Map.of(
                "batchNumber", BATCH,
                "productCategory", CATEGORY,
                "ingredients", List.of(ing));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("VALIDATION", data.get("mode"));
        assertEquals(0, data.get("compliantCount"));
        assertEquals(1, data.get("exceededCount"));
        assertEquals(false, data.get("passed"));
        List<Map<String, Object>> exceeded = (List<Map<String, Object>>) data.get("exceeded");
        assertEquals("亚硝酸钠", exceeded.get(0).get("additiveName"));
    }

    @Test
    @DisplayName("UT-ACC-03: VALIDATION mode — usage <= limit → COMPLIANT")
    @SuppressWarnings("unchecked")
    void allCompliant() throws Exception {
        AdditiveLimit nitrite = AdditiveLimit.builder()
                .additiveCode("INS 250").additiveName("亚硝酸钠")
                .foodCategory(CATEGORY)
                .maxLimit(new BigDecimal("30"))
                .unit("mg/kg").regulationRef("GB 2760-2014")
                .build();
        when(additiveLimitRepository.findByAdditiveCodeAndFoodCategoryAndActiveTrue(
                "INS 250", CATEGORY)).thenReturn(Optional.of(nitrite));

        Map<String, Object> ing = Map.of(
                "additiveCode", "INS 250",
                "usageAmount", new BigDecimal("20"));  // 20 <= 30
        Map<String, Object> params = Map.of(
                "batchNumber", BATCH,
                "productCategory", CATEGORY,
                "ingredients", List.of(ing));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("compliantCount"));
        assertEquals(0, data.get("exceededCount"));
        assertEquals(true, data.get("passed"));
    }

    @Test
    @DisplayName("UT-ACC-04: REFERENCE mode — no ingredients → returns category reference list")
    @SuppressWarnings("unchecked")
    void referenceMode() throws Exception {
        AdditiveLimit l1 = AdditiveLimit.builder()
                .additiveCode("INS 250").additiveName("亚硝酸钠")
                .foodCategory(CATEGORY).maxLimit(new BigDecimal("30"))
                .unit("mg/kg").regulationRef("GB 2760-2014").build();
        when(additiveLimitRepository.findByFoodCategoryAndActiveTrue(CATEGORY))
                .thenReturn(List.of(l1));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "productCategory", CATEGORY);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("REFERENCE", data.get("mode"));
        assertEquals(1, data.get("referenceCount"));
    }

    @Test
    @DisplayName("UT-ACC-05: unknown additive (not in GB 2760) → unknown list")
    @SuppressWarnings("unchecked")
    void unknownAdditive() throws Exception {
        when(additiveLimitRepository.findByAdditiveCodeAndFoodCategoryAndActiveTrue(
                anyString(), anyString())).thenReturn(Optional.empty());

        Map<String, Object> ing = Map.of(
                "additiveCode", "INS 999",
                "usageAmount", new BigDecimal("100"));
        Map<String, Object> params = Map.of(
                "batchNumber", BATCH,
                "ingredients", List.of(ing));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("unknownCount"));
        assertEquals(0, data.get("compliantCount"));
        assertEquals(0, data.get("exceededCount"));
    }

    // ── helpers ──
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
