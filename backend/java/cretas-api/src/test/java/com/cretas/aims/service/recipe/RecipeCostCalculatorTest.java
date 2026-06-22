package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RecipeCostCalculator 锅序调料成本")
class RecipeCostCalculatorTest {

    private ProductRecipe recipe(String ratio) {
        ProductRecipe r = new ProductRecipe();
        r.setSubsequentPotRatio(new BigDecimal(ratio));
        return r;
    }

    private RecipeIngredient ing(String section, String name, String dosageG,
                                 String p1, String p2, boolean countIn) {
        RecipeIngredient i = new RecipeIngredient();
        i.setSection(section);
        i.setName(name);
        i.setDosagePerKgG(new BigDecimal(dosageG));
        i.setPriceSource1(p1 == null ? null : new BigDecimal(p1));
        i.setPriceSource2(p2 == null ? null : new BigDecimal(p2));
        i.setCountInSeasoning(countIn);
        return i;
    }

    // 注射/kg = Σ INJECTION: dosage_g/1000 × max(p1,p2)
    @Test
    @DisplayName("注射/kg 取两源最高")
    void injectionPerKg_takesMaxPrice() {
        // 料A: 1000g/kg × max(2, 5)=5 → 5.0 ; 料B: 500g/kg × (p2 null→p1=4) → 2.0
        List<RecipeIngredient> ings = List.of(
                ing("INJECTION", "A", "1000", "2", "5", true),
                ing("INJECTION", "B", "500", "4", null, true));
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, new BigDecimal("10"), List.of(new BigDecimal("10")));
        assertEquals(new BigDecimal("7.0000"), c.getInjectionCostPerKg());
    }

    // 熟制全量/kg 排除 count_in_seasoning=false(老汤)
    @Test
    @DisplayName("熟制全量/kg 排除老汤")
    void cookingPerKg_excludesOldSoup() {
        List<RecipeIngredient> ings = List.of(
                ing("COOKING", "八角", "1000", "1", null, true),    // 1.0/kg
                ing("COOKING", "高汤", "1000", "99", null, false)); // 老汤, 不计
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, BigDecimal.ZERO, List.of(new BigDecimal("100")));
        assertEquals(new BigDecimal("1.0000"), c.getCookingFullCostPerKg());
    }

    // N=1: 熟制总 = R × cookFull × 1
    @Test
    @DisplayName("N=1 第一锅全量")
    void cooking_onePot_full() {
        List<RecipeIngredient> ings = List.of(ing("COOKING", "料", "1000", "1", null, true)); // 1.0/kg
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, BigDecimal.ZERO, List.of(new BigDecimal("160")));
        assertEquals(new BigDecimal("160.0000"), c.getCookingTotal());
    }

    // N=2 等锅: pot1×1 + pot2×ratio
    @Test
    @DisplayName("N=2 第二锅 1/3")
    void cooking_twoPots_secondThird() {
        List<RecipeIngredient> ings = List.of(ing("COOKING", "料", "1000", "3", null, true)); // 3.0/kg
        // 80kg×3×1 + 80kg×3×0.3333 = 240.0000 + 79.9920 = 319.9920
        // (ratio=0.3333 ≠ exact 1/3; plan comment says "320" but that requires exact 1/3)
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, BigDecimal.ZERO,
                List.of(new BigDecimal("80"), new BigDecimal("80")));
        assertEquals(new BigDecimal("319.9920"), c.getCookingTotal());
    }

    // N=3: pot1 full, pot2&3 ratio
    @Test
    @DisplayName("N=3 第三锅同第二锅(ratio)")
    void cooking_threePots() {
        List<RecipeIngredient> ings = List.of(ing("COOKING", "料", "1000", "3", null, true));
        // 100×3×1 + 100×3×0.5 + 100×3×0.5 = 300 + 150 + 150 = 600 (用 ratio=0.5 验可配)
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.5"), ings, BigDecimal.ZERO,
                List.of(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
        assertEquals(new BigDecimal("600.0000"), c.getCookingTotal());
    }

    // 注射 R 与熟制锅原料独立
    @Test
    @DisplayName("注射总用注射R, 与熟制锅独立")
    void injectionTotal_usesInjectionR() {
        List<RecipeIngredient> ings = List.of(
                ing("INJECTION", "A", "1000", "1", null, true),  // 1.0/kg
                ing("COOKING", "料", "1000", "2", null, true));  // 2.0/kg
        // 注射总 = 307 × 1.0 = 307 ; 熟制总 = 160 × 2.0 × 1 = 320 ; total=627
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, new BigDecimal("307"), List.of(new BigDecimal("160")));
        assertEquals(new BigDecimal("307.0000"), c.getInjectionTotal());
        assertEquals(new BigDecimal("320.0000"), c.getCookingTotal());
        assertEquals(new BigDecimal("627.0000"), c.getTotal());
    }
}
