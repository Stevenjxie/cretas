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
}
