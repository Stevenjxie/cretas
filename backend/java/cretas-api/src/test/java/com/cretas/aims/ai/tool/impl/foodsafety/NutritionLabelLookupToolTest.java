package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.NutritionLabel;
import com.cretas.aims.repository.foodsafety.NutritionLabelRepository;
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
import static org.mockito.Mockito.when;

/** Unit tests for {@link NutritionLabelLookupTool} (Sprint 9 P2.B). */
@ExtendWith(MockitoExtension.class)
class NutritionLabelLookupToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private NutritionLabelLookupTool tool;

    @Mock
    private NutritionLabelRepository nutritionLabelRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-NLL-01: metadata + READ default")
    void metadata() {
        assertEquals("nutrition_label_lookup", tool.getToolName());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-NLL-02: by productCode → labels list returned")
    @SuppressWarnings("unchecked")
    void byProductCode() throws Exception {
        NutritionLabel l = buildLabel(1L, "QHJ-LZT-200G", "卤猪蹄 200g");
        when(nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(FACTORY_ID, "QHJ-LZT-200G"))
                .thenReturn(List.of(l));

        Map<String, Object> params = Map.of("productCode", "QHJ-LZT-200G");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("totalCount"));
        List<Map<String, Object>> labels = (List<Map<String, Object>>) data.get("labels");
        assertEquals("QHJ-LZT-200G", labels.get(0).get("productCode"));
    }

    @Test
    @DisplayName("UT-NLL-03: by productName fuzzy → labels list")
    @SuppressWarnings("unchecked")
    void byProductName() throws Exception {
        NutritionLabel l = buildLabel(2L, "QHJ-LZT-300G", "卤猪蹄 300g");
        when(nutritionLabelRepository
                .findByFactoryIdAndProductNameContainingOrderByCreatedAtDesc(FACTORY_ID, "猪蹄"))
                .thenReturn(List.of(l));

        Map<String, Object> params = Map.of("productName", "猪蹄");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("totalCount"));
    }

    @Test
    @DisplayName("UT-NLL-04: no labels → empty list + actionHint to /generate")
    @SuppressWarnings("unchecked")
    void noLabels() throws Exception {
        when(nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(FACTORY_ID, "X"))
                .thenReturn(List.of());

        Map<String, Object> params = Map.of("productCode", "X");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("totalCount"));
        String message = (String) result.get("message");
        assertTrue(message.contains("未找到") || message.contains("nutrition_label_generate"));
    }

    // ── helpers ──
    private NutritionLabel buildLabel(Long id, String code, String name) {
        NutritionLabel l = NutritionLabel.builder()
                .factoryId(FACTORY_ID)
                .productCode(code)
                .productName(name)
                .bomVersionId("v1")
                .servingSize(new BigDecimal("200"))
                .energyPerServing(new BigDecimal("1500"))
                .proteinPerServing(new BigDecimal("40"))
                .fatPerServing(new BigDecimal("30"))
                .carbohydratePerServing(BigDecimal.ZERO)
                .sodiumPerServing(new BigDecimal("500"))
                .generatedByUserId(1L)
                .status("DRAFT")
                .build();
        l.setId(id);
        return l;
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
