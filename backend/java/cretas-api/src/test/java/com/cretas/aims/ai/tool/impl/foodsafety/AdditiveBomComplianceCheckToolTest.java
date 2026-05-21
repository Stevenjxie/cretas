package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdditiveBomComplianceCheckTool} (Sprint 9 P2.F V2). */
@ExtendWith(MockitoExtension.class)
class AdditiveBomComplianceCheckToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String CATEGORY = "08.02 熟肉制品";
    private static final String RECIPE_ID = "11111111-2222-3333-4444-555555555555";
    private static final String PRODUCT_TYPE_ID = "P-叮咚卤猪蹄";

    @InjectMocks
    private AdditiveBomComplianceCheckTool tool;

    @Mock
    private BomRecipeRepository bomRecipeRepository;

    @Mock
    private BomRecipeItemRepository bomRecipeItemRepository;

    @Mock
    private AdditiveLimitRepository additiveLimitRepository;

    @Mock
    private AdditiveSmartMatchTool additiveSmartMatchTool;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ABCC-01: metadata")
    void metadata() {
        assertEquals("additive_bom_compliance_check", tool.getToolName());
    }

    @Test
    @DisplayName("UT-ABCC-02: BOM_NOT_FOUND → friendly hint + actionHint")
    @SuppressWarnings("unchecked")
    void bomNotFound() throws Exception {
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_TYPE_ID, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of("productTypeId", PRODUCT_TYPE_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("BOM_NOT_FOUND", data.get("status"));
        assertTrue(((String) data.get("actionHint")).contains("/bom/recipes"));
    }

    @Test
    @DisplayName("UT-ABCC-03: MISSING_PARAM when neither productTypeId nor bomRecipeId given")
    @SuppressWarnings("unchecked")
    void missingParam() throws Exception {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("MISSING_PARAM", data.get("status"));
    }

    @Test
    @DisplayName("UT-ABCC-04: EMPTY_BOM when recipe has no items")
    @SuppressWarnings("unchecked")
    void emptyBom() throws Exception {
        BomRecipe recipe = buildRecipe();
        when(bomRecipeRepository.findById(RECIPE_ID))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(RECIPE_ID))
                .thenReturn(List.of());

        Map<String, Object> params = Map.of("bomRecipeId", RECIPE_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("EMPTY_BOM", data.get("status"));
        assertEquals("BOM-20260521-001", data.get("recipeCode"));
        assertEquals("叮咚卤猪蹄 200g", data.get("productName"));
    }

    @Test
    @DisplayName("UT-ABCC-05: full compliance — all green")
    @SuppressWarnings("unchecked")
    void allGreenCompliance() throws Exception {
        BomRecipe recipe = buildRecipe();
        // item: 亚硝酸钠 0.005g per 200g serving → perKgUsage = 0.005 * 1000 / 200 = 0.025 mg/kg (≪ 30)
        BomRecipeItem item = buildItem(1L, "亚硝酸钠", new BigDecimal("0.005"), "g");
        AdditiveLimit nitrite = buildLimit("亚硝酸钠", "INS 250", CATEGORY, new BigDecimal("30"));

        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(RECIPE_ID))
                .thenReturn(List.of(item));
        when(additiveLimitRepository.findByAdditiveNameAndActiveTrue("亚硝酸钠"))
                .thenReturn(List.of(nitrite));

        Map<String, Object> params = Map.of("bomRecipeId", RECIPE_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(1, summary.get("totalAdditivesChecked"));
        assertEquals(1, summary.get("greenCount"));
        assertEquals(0, summary.get("redCount"));
        assertEquals(true, summary.get("overallPass"));
    }

    @Test
    @DisplayName("UT-ABCC-06: RED EXCEEDED — perKgUsage > maxLimit")
    @SuppressWarnings("unchecked")
    void redExceeded() throws Exception {
        BomRecipe recipe = buildRecipe();
        // 苯甲酸钠 5g per 200g → perKgUsage = 5 * 1000 / 200 = 25 mg/kg → much greater than 1 mg limit
        BomRecipeItem item = buildItem(1L, "苯甲酸钠", new BigDecimal("5.0"), "g");
        AdditiveLimit benzoate = buildLimit("苯甲酸钠", "INS 211", CATEGORY, new BigDecimal("10"));  // low limit

        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(RECIPE_ID))
                .thenReturn(List.of(item));
        when(additiveLimitRepository.findByAdditiveNameAndActiveTrue("苯甲酸钠"))
                .thenReturn(List.of(benzoate));

        Map<String, Object> params = Map.of("bomRecipeId", RECIPE_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(1, summary.get("redCount"));
        assertEquals(false, summary.get("overallPass"));

        List<Map<String, Object>> report = (List<Map<String, Object>>) data.get("additiveReport");
        assertEquals("RED_EXCEEDED", report.get(0).get("status"));
    }

    @Test
    @DisplayName("UT-ABCC-07: factory mismatch → FACTORY_MISMATCH status")
    @SuppressWarnings("unchecked")
    void factoryMismatch() throws Exception {
        BomRecipe recipe = buildRecipe();
        recipe.setFactoryId("F001");  // 不同工厂

        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        Map<String, Object> params = Map.of("bomRecipeId", RECIPE_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("FACTORY_MISMATCH", data.get("status"));
    }

    @Test
    @DisplayName("UT-ABCC-08: 非添加剂 item (普通原料) → skip, not in report")
    @SuppressWarnings("unchecked")
    void skipNonAdditive() throws Exception {
        BomRecipe recipe = buildRecipe();
        BomRecipeItem item = buildItem(1L, "猪蹄", new BigDecimal("150.0"), "g");

        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(RECIPE_ID))
                .thenReturn(List.of(item));
        // 猪蹄 不是添加剂 → 所有 lookups return empty
        when(additiveLimitRepository.findByAdditiveNameAndActiveTrue("猪蹄")).thenReturn(List.of());
        lenient().when(additiveLimitRepository.findByAliasContaining("猪蹄")).thenReturn(List.of());
        when(additiveLimitRepository.findByAdditiveNameContainingAndActiveTrue("猪蹄")).thenReturn(List.of());

        Map<String, Object> params = Map.of("bomRecipeId", RECIPE_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(0, summary.get("totalAdditivesChecked"));  // 0 added in report
    }

    // ── helpers ──
    private BomRecipe buildRecipe() {
        return BomRecipe.builder()
                .id(RECIPE_ID)
                .factoryId(FACTORY_ID)
                .recipeCode("BOM-20260521-001")
                .productTypeId(PRODUCT_TYPE_ID)
                .productName("叮咚卤猪蹄 200g")
                .version(1)
                .isCurrent(true)
                .outputQuantityPerUnit(new BigDecimal("200"))
                .outputUnit("g")
                .status(BomRecipe.Status.ACTIVE)
                .build();
    }

    private BomRecipeItem buildItem(Long id, String materialName, BigDecimal qty, String unit) {
        return BomRecipeItem.builder()
                .id(id)
                .recipeId(RECIPE_ID)
                .factoryId(FACTORY_ID)
                .materialTypeId("MAT-" + id)
                .materialName(materialName)
                .standardQuantity(qty)
                .unit(unit)
                .sortOrder(0)
                .build();
    }

    private AdditiveLimit buildLimit(String name, String code, String category, BigDecimal max) {
        return AdditiveLimit.builder()
                .additiveCode(code)
                .additiveName(name)
                .foodCategory(category)
                .maxLimit(max)
                .unit("mg/kg")
                .regulationRef("GB 2760-2014 表 A.1")
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
