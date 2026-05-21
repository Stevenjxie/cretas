package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.foodsafety.IngredientNutritionFact;
import com.cretas.aims.entity.foodsafety.NutritionLabel;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.foodsafety.IngredientNutritionFactRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit tests for {@link NutritionLabelGenerateTool} (Sprint 9 P2.B). */
@ExtendWith(MockitoExtension.class)
class NutritionLabelGenerateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_CODE = "QHJ-LZT-200G";
    private static final String BOM_ID = "bom-uuid-001";

    @InjectMocks
    private NutritionLabelGenerateTool tool;

    @Mock
    private BomRecipeRepository bomRecipeRepository;

    @Mock
    private BomVersionRepository bomVersionRepository;

    @Mock
    private IngredientNutritionFactRepository ingredientNutritionFactRepository;

    @Mock
    private NutritionLabelRepository nutritionLabelRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-NLG-01: metadata + supportsPreview + WRITE")
    void metadata() {
        assertEquals("nutrition_label_generate", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-NLG-02: doPreview — no BOM → canDo=false + missingBom=true")
    @SuppressWarnings("unchecked")
    void previewMissingBomCannotGenerate() throws Exception {
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_CODE, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of("productCode", PRODUCT_CODE);
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals("PREVIEW", result.get("status"));
        assertEquals(false, result.get("canDo"));
        assertEquals(true, result.get("missingBom"));
        assertNotNull(result.get("actionHint"));
    }

    @Test
    @DisplayName("UT-NLG-03: doPreview — happy path with BOM + matched ingredients → canDo=true")
    @SuppressWarnings("unchecked")
    void previewHappyPath() throws Exception {
        BomRecipe recipe = buildRecipe();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_CODE, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));

        IngredientNutritionFact pigTrotter = buildFact("猪蹄", "08-1-001",
                new BigDecimal("1004.20"), new BigDecimal("22.60"));
        when(ingredientNutritionFactRepository.findByIngredientNameContainingAndActiveTrue("猪蹄"))
                .thenReturn(List.of(pigTrotter));

        when(nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(FACTORY_ID, PRODUCT_CODE))
                .thenReturn(new ArrayList<>());

        Map<String, Object> params = Map.of("productCode", PRODUCT_CODE);
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals(PRODUCT_CODE, result.get("productCode"));
        assertEquals(1, result.get("matchedIngredientCount"));
        // 200g servingSize / 200g recipe = scaling 1.0
        // 200g 猪蹄 * 22.60 / 100 * 1.0 = 45.20 g protein
        assertEquals(new BigDecimal("45.20"), result.get("proteinPerServing"));
    }

    @Test
    @DisplayName("UT-NLG-04: doExecute — happy path → CREATED, NutritionLabel saved")
    @SuppressWarnings("unchecked")
    void executeHappyPathSaves() throws Exception {
        BomRecipe recipe = buildRecipe();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_CODE, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));

        IngredientNutritionFact pigTrotter = buildFact("猪蹄", "08-1-001",
                new BigDecimal("1004.20"), new BigDecimal("22.60"));
        when(ingredientNutritionFactRepository.findByIngredientNameContainingAndActiveTrue("猪蹄"))
                .thenReturn(List.of(pigTrotter));

        when(nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(FACTORY_ID, PRODUCT_CODE))
                .thenReturn(new ArrayList<>());

        Map<String, Object> params = Map.of("productCode", PRODUCT_CODE);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("CREATED", data.get("status"));
        assertEquals(PRODUCT_CODE, data.get("productCode"));
        assertNotNull(data.get("labelMarkdown"));
        assertTrue(((String) data.get("labelMarkdown")).contains("GB 28050-2011"));
        verify(nutritionLabelRepository, times(1)).save(any(NutritionLabel.class));
    }

    @Test
    @DisplayName("UT-NLG-05: doExecute — R4 idempotent: existing DRAFT → ALREADY_EXISTS, no save")
    @SuppressWarnings("unchecked")
    void executeIdempotent() throws Exception {
        BomRecipe recipe = buildRecipe();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_CODE, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));

        NutritionLabel existing = NutritionLabel.builder()
                .factoryId(FACTORY_ID)
                .productCode(PRODUCT_CODE)
                .productName("叮咚好食光卤猪蹄 200g")
                .bomVersionId(BOM_ID)
                .servingSize(new BigDecimal("200"))
                .energyPerServing(new BigDecimal("100"))
                .proteinPerServing(new BigDecimal("10"))
                .fatPerServing(new BigDecimal("5"))
                .carbohydratePerServing(BigDecimal.ZERO)
                .sodiumPerServing(new BigDecimal("100"))
                .generatedByUserId(1L)
                .status("DRAFT")
                .build();
        existing.setId(999L);
        when(nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(FACTORY_ID, PRODUCT_CODE))
                .thenReturn(List.of(existing));

        Map<String, Object> params = Map.of("productCode", PRODUCT_CODE);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("ALREADY_EXISTS", data.get("status"));
        assertEquals(999L, data.get("existingLabelId"));
        verify(nutritionLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-NLG-06: doExecute — no BOM → BusinessException 404")
    void executeNoBomThrows() {
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("productCode", "MISSING");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-NLG-07: doExecute — unmatched ingredient → missingIngredients populated")
    @SuppressWarnings("unchecked")
    void executeWithMissingIngredient() throws Exception {
        BomRecipe recipe = buildRecipe();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY_ID, PRODUCT_CODE, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        // 营养素库不返回任何 match → 猪蹄 算 missing
        when(ingredientNutritionFactRepository.findByIngredientNameContainingAndActiveTrue(anyString()))
                .thenReturn(new ArrayList<>());

        when(nutritionLabelRepository
                .findByFactoryIdAndProductCodeOrderByCreatedAtDesc(FACTORY_ID, PRODUCT_CODE))
                .thenReturn(new ArrayList<>());

        Map<String, Object> params = Map.of("productCode", PRODUCT_CODE);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("CREATED", data.get("status"));
        assertEquals(0, data.get("matchedIngredientCount"));
        List<Map<String, Object>> missing = (List<Map<String, Object>>) data.get("missingIngredients");
        assertFalse(missing.isEmpty());
        assertEquals("猪蹄", missing.get(0).get("materialName"));
    }

    // ── helpers ──
    private BomRecipe buildRecipe() {
        BomRecipe recipe = BomRecipe.builder()
                .id(BOM_ID)
                .factoryId(FACTORY_ID)
                .recipeCode("BOM-20260521-001")
                .productTypeId(PRODUCT_CODE)
                .productName("叮咚好食光卤猪蹄 200g")
                .version(1)
                .isCurrent(true)
                .outputQuantityPerUnit(new BigDecimal("200.00"))
                .outputUnit("g")
                .status(BomRecipe.Status.ACTIVE)
                .build();
        BomRecipeItem item = BomRecipeItem.builder()
                .recipeId(BOM_ID)
                .factoryId(FACTORY_ID)
                .materialTypeId("MT-PIG-TROTTER")
                .materialName("猪蹄")
                .standardQuantity(new BigDecimal("200.0000"))
                .actualQuantity(new BigDecimal("200.0000"))
                .yieldRate(new BigDecimal("100.00"))
                .unit("g")
                .build();
        recipe.setItems(List.of(item));
        return recipe;
    }

    private IngredientNutritionFact buildFact(String name, String code,
            BigDecimal energy, BigDecimal protein) {
        return IngredientNutritionFact.builder()
                .ingredientName(name)
                .ingredientCode(code)
                .per100gEnergy(energy)
                .per100gProtein(protein)
                .per100gFat(new BigDecimal("18.80"))
                .per100gCarbohydrate(BigDecimal.ZERO)
                .per100gSodium(new BigDecimal("80.00"))
                .per100gSaturatedFat(new BigDecimal("6.80"))
                .build();
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 22L);
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
