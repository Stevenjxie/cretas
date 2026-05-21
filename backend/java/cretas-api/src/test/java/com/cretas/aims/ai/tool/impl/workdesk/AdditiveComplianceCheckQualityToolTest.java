package com.cretas.aims.ai.tool.impl.workdesk;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdditiveComplianceCheckQualityTool} (Sprint 8 P4c). */
@ExtendWith(MockitoExtension.class)
class AdditiveComplianceCheckQualityToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AdditiveComplianceCheckQualityTool tool;

    @Mock
    private AdditiveLimitRepository additiveLimitRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ACQ-01: metadata — name + required")
    void metadata() {
        assertEquals("additive_compliance_check_quality", tool.getToolName());
        assertEquals(List.of("batchNumber"), tool.getRequiredParameters());
        assertTrue(tool.getDescription().contains("GB 2760"));
    }

    @Test
    @DisplayName("UT-ACQ-02: no ingredients — R5 dead-end + BLOCK_RELEASE_NO_DATA + actionHint")
    @SuppressWarnings("unchecked")
    void noIngredients() throws Exception {
        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-X"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(false, data.get("hasIngredients"));
        assertEquals("BLOCK_RELEASE_NO_DATA", data.get("releaseHint"));
        assertNull(data.get("compliant"));
        assertNotNull(data.get("actionHint"));
        assertTrue(((String) data.get("actionHint")).contains("/production/bom"));
    }

    @Test
    @DisplayName("UT-ACQ-03: all compliant — RELEASE_OK + compliant=true")
    @SuppressWarnings("unchecked")
    void allCompliant() throws Exception {
        AdditiveLimit limit = new AdditiveLimit();
        limit.setAdditiveCode("INS 250");
        limit.setAdditiveName("亚硝酸钠");
        limit.setMaxLimit(new BigDecimal("30"));
        limit.setUnit("mg/kg");
        when(additiveLimitRepository
                .findByAdditiveCodeAndFoodCategoryAndActiveTrue(anyString(), anyString()))
                .thenReturn(Optional.of(limit));

        List<Map<String, Object>> ingredients = List.of(
                Map.of("additiveCode", "INS 250", "usageAmount", 20, "unit", "mg/kg"));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-Y", "ingredients", ingredients), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("hasIngredients"));
        assertEquals(true, data.get("compliant"));
        assertEquals("RELEASE_OK", data.get("releaseHint"));
        assertEquals(0, data.get("violationCount"));
    }

    @Test
    @DisplayName("UT-ACQ-04: exceeded — BLOCK_RELEASE + compliant=false + violation listed")
    @SuppressWarnings("unchecked")
    void exceeded() throws Exception {
        AdditiveLimit limit = new AdditiveLimit();
        limit.setAdditiveCode("INS 250");
        limit.setAdditiveName("亚硝酸钠");
        limit.setMaxLimit(new BigDecimal("30"));
        limit.setUnit("mg/kg");
        when(additiveLimitRepository
                .findByAdditiveCodeAndFoodCategoryAndActiveTrue(anyString(), anyString()))
                .thenReturn(Optional.of(limit));

        List<Map<String, Object>> ingredients = List.of(
                Map.of("additiveCode", "INS 250", "usageAmount", 50, "unit", "mg/kg"));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-Z", "ingredients", ingredients), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(false, data.get("compliant"));
        assertEquals("BLOCK_RELEASE", data.get("releaseHint"));
        assertEquals(1, data.get("violationCount"));
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
