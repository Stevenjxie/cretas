package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.IngredientNutritionFact;
import com.cretas.aims.repository.foodsafety.IngredientNutritionFactRepository;
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
import static org.mockito.Mockito.when;

/** Unit tests for {@link IngredientNutritionFactQueryTool} (Sprint 9 P2.B). */
@ExtendWith(MockitoExtension.class)
class IngredientNutritionFactQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private IngredientNutritionFactQueryTool tool;

    @Mock
    private IngredientNutritionFactRepository ingredientNutritionFactRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-INFQ-01: metadata + READ default")
    void metadata() {
        assertEquals("ingredient_nutrition_fact_query", tool.getToolName());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-INFQ-02: by ingredientCode (precise) → single fact")
    @SuppressWarnings("unchecked")
    void byCodePrecise() throws Exception {
        IngredientNutritionFact f = buildFact("猪蹄", "08-1-001");
        when(ingredientNutritionFactRepository.findByIngredientCodeAndActiveTrue("08-1-001"))
                .thenReturn(Optional.of(f));

        Map<String, Object> params = Map.of("ingredientCode", "08-1-001");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("totalCount"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("ingredients");
        assertEquals("08-1-001", rows.get(0).get("ingredientCode"));
    }

    @Test
    @DisplayName("UT-INFQ-03: by ingredientName fuzzy → list")
    @SuppressWarnings("unchecked")
    void byNameFuzzy() throws Exception {
        IngredientNutritionFact a = buildFact("老抽 (酱油)", "24-1-001");
        IngredientNutritionFact b = buildFact("生抽 (酱油)", "24-1-002");
        when(ingredientNutritionFactRepository.findByIngredientNameContainingAndActiveTrue("酱油"))
                .thenReturn(List.of(a, b));

        Map<String, Object> params = Map.of("ingredientName", "酱油");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(2, data.get("totalCount"));
    }

    @Test
    @DisplayName("UT-INFQ-04: no filter → returns all active")
    @SuppressWarnings("unchecked")
    void noFilterReturnsAll() throws Exception {
        when(ingredientNutritionFactRepository.findByActiveTrue())
                .thenReturn(List.of(buildFact("猪蹄", "08-1-001"), buildFact("鸡爪", "08-1-002")));

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(2, data.get("totalCount"));
    }

    @Test
    @DisplayName("UT-INFQ-05: code not found → empty + helpful message")
    @SuppressWarnings("unchecked")
    void codeNotFound() throws Exception {
        when(ingredientNutritionFactRepository.findByIngredientCodeAndActiveTrue("XX-9-999"))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of("ingredientCode", "XX-9-999");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("totalCount"));
        String message = (String) result.get("message");
        assertTrue(message.contains("未收录"));
    }

    // ── factoryId 隔离豁免验证 (EXEMPT: 全局参考数据) ──────────────────────

    @Test
    @DisplayName("UT-INFQ-ISO-01: EXEMPT — global reference data, same result regardless of factoryId")
    @SuppressWarnings("unchecked")
    void exemptGlobalDataSameForAllFactories() throws Exception {
        IngredientNutritionFact f = buildFact("猪蹄", "08-1-001");
        when(ingredientNutritionFactRepository.findByIngredientCodeAndActiveTrue("08-1-001"))
                .thenReturn(Optional.of(f));

        Map<String, Object> params = Map.of("ingredientCode", "08-1-001");

        // Factory A result
        Map<String, Object> resultA = invoke("doExecute", "F001", params, ctxFor("F001"));
        // Factory B result — same global data, must return identical count
        Map<String, Object> resultB = invoke("doExecute", "F999", params, ctxFor("F999"));

        Map<String, Object> dataA = (Map<String, Object>) resultA.get("data");
        Map<String, Object> dataB = (Map<String, Object>) resultB.get("data");
        assertEquals(dataA.get("totalCount"), dataB.get("totalCount"),
                "Global reference data must return same result for all factories (EXEMPT)");
        assertEquals(1, dataA.get("totalCount"));
    }

    // ── helpers ──
    private IngredientNutritionFact buildFact(String name, String code) {
        return IngredientNutritionFact.builder()
                .ingredientName(name)
                .ingredientCode(code)
                .per100gEnergy(new BigDecimal("1000.00"))
                .per100gProtein(new BigDecimal("20.00"))
                .per100gFat(new BigDecimal("15.00"))
                .per100gCarbohydrate(new BigDecimal("5.00"))
                .per100gSodium(new BigDecimal("100.00"))
                .sourceRef("中国食物成分表 第6版")
                .build();
    }

    private Map<String, Object> ctx() {
        return ctxFor(FACTORY_ID);
    }

    private Map<String, Object> ctxFor(String factoryId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
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
