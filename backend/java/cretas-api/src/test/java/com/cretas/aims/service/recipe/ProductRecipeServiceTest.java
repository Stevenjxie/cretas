package com.cretas.aims.service.recipe;

import com.cretas.aims.dto.recipe.RecipeIngredientDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.recipe.impl.ProductRecipeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductRecipeService")
class ProductRecipeServiceTest {

    private static final String F = "DEMO_FACTORY";

    @Mock ProductRecipeRepository recipeRepo;
    @Mock RecipeIngredientRepository ingredientRepo;

    ProductRecipeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductRecipeServiceImpl(recipeRepo, ingredientRepo);
    }

    private SaveRecipeRequest req() {
        SaveRecipeRequest r = new SaveRecipeRequest();
        r.setProductTypeId("DF_pt10");
        r.setName("M67卤牛肉");
        r.setSubsequentPotRatio(new BigDecimal("0.3333"));
        RecipeIngredientDTO i = new RecipeIngredientDTO();
        i.setSection("COOKING");
        i.setName("料");
        i.setDosagePerKgG(new BigDecimal("1000"));
        i.setPriceSource1(new BigDecimal("1"));
        i.setCountInSeasoning(true);
        r.setIngredients(List.of(i));
        return r;
    }

    @Test
    @DisplayName("create 同 SKU 已有 ACTIVE 配方 → 409")
    void create_duplicateActive_throws409() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.of(new ProductRecipe()));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(F, req()));
        assertEquals(409, ex.getCode());
        verify(recipeRepo, never()).save(any());
    }

    @Test
    @DisplayName("get 跨租户(不存在于本厂) → 404")
    void get_otherFactory_throws404() {
        when(recipeRepo.findByFactoryIdAndId(F, "x")).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.get(F, "x"));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("create 成功并装配 per-kg 速率")
    void create_ok_assemblesRates() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(recipeRepo.save(any(ProductRecipe.class))).thenAnswer(inv -> {
            ProductRecipe p = inv.getArgument(0);
            if (p.getId() == null) p.setId("R-1");
            return p;
        });
        when(ingredientRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var dto = service.create(F, req());
        // 熟制全量/kg = 1000/1000 × 1 = 1.0; 第一锅每kg = 注射0 + 熟制1.0
        assertEquals(0, new BigDecimal("1.0000").compareTo(dto.getCookingFullCostPerKg()));
        assertEquals(0, new BigDecimal("1.0000").compareTo(dto.getCostPerKgFirstPot()));
    }

    // ─────────────────────────────────────────────────────────────
    // SP-F Fix 2 — 老汤(countInSeasoning=false) 不要求单价
    // ─────────────────────────────────────────────────────────────

    /**
     * V1 — 老汤(countInSeasoning=false) 无单价 → validation 通过.
     * 老汤不计入调料成本(RecipeCostCalculator 跳过), 强制填单价无意义(防呆 Rule).
     */
    @Test
    @DisplayName("V1: 老汤(countInSeasoning=false) 无单价 → validation 通过(create 成功)")
    void v1_laotang_noPriceRequired() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(recipeRepo.save(any(ProductRecipe.class))).thenAnswer(inv -> {
            ProductRecipe p = inv.getArgument(0);
            if (p.getId() == null) p.setId("R-LT");
            return p;
        });
        when(ingredientRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaveRecipeRequest r = new SaveRecipeRequest();
        r.setProductTypeId("DF_pt10");
        r.setName("老汤测试配方");
        r.setSubsequentPotRatio(new BigDecimal("0.3333"));

        // 老汤: countInSeasoning=false, 无单价 — should NOT throw
        RecipeIngredientDTO laotang = new RecipeIngredientDTO();
        laotang.setSection("COOKING");
        laotang.setName("老汤");
        laotang.setDosagePerKgG(new BigDecimal("500"));
        laotang.setPriceSource1(null); // no price
        laotang.setPriceSource2(null); // no price
        laotang.setCountInSeasoning(false); // 老汤

        // Also add a normal ingredient that counts, with a price
        RecipeIngredientDTO normal = new RecipeIngredientDTO();
        normal.setSection("COOKING");
        normal.setName("香料");
        normal.setDosagePerKgG(new BigDecimal("100"));
        normal.setPriceSource1(new BigDecimal("5.00"));
        normal.setCountInSeasoning(true);

        r.setIngredients(List.of(laotang, normal));

        // Must NOT throw
        assertDoesNotThrow(() -> service.create(F, r));
    }

    /**
     * V2 — 普通调料(countInSeasoning=true 或 null) 无单价 → validation 失败(400).
     * 计入成本的原料必须有单价，否则调料成本会是 ¥0 静默丢失。
     */
    @Test
    @DisplayName("V2: 普通调料(countInSeasoning=true) 无单价 → BusinessException(400)")
    void v2_normalIngredient_noPriceRequired_throws400() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.empty());

        SaveRecipeRequest r = new SaveRecipeRequest();
        r.setProductTypeId("DF_pt10");
        r.setName("无价格配方");
        r.setSubsequentPotRatio(new BigDecimal("0.3333"));

        RecipeIngredientDTO noPrice = new RecipeIngredientDTO();
        noPrice.setSection("COOKING");
        noPrice.setName("花椒");
        noPrice.setDosagePerKgG(new BigDecimal("50"));
        noPrice.setPriceSource1(null); // missing price
        noPrice.setPriceSource2(null);
        noPrice.setCountInSeasoning(true); // counts in cost → price required

        r.setIngredients(List.of(noPrice));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(F, r));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("花椒"), "error message should name the ingredient");
    }

    /**
     * V3 — countInSeasoning=null (default, treats as true) 无单价 → validation 失败(400).
     * null 视同 true (saveIngredients 默认值逻辑的对称测试).
     */
    @Test
    @DisplayName("V3: countInSeasoning=null (视同 true) 无单价 → BusinessException(400)")
    void v3_nullCountInSeasoning_noPriceRequired_throws400() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.empty());

        SaveRecipeRequest r = new SaveRecipeRequest();
        r.setProductTypeId("DF_pt10");
        r.setName("缺单价配方");

        RecipeIngredientDTO noPrice = new RecipeIngredientDTO();
        noPrice.setSection("COOKING");
        noPrice.setName("盐");
        noPrice.setDosagePerKgG(new BigDecimal("20"));
        noPrice.setPriceSource1(null);
        noPrice.setPriceSource2(null);
        noPrice.setCountInSeasoning(null); // null → treated as true → price required

        r.setIngredients(List.of(noPrice));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(F, r));
        assertEquals(400, ex.getCode());
    }
}
