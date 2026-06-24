package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.entity.recipe.SeasoningLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 调料成本纯函数(无状态). Spec 2026-06-22 §3.2.
 * 注射/kg = Σ INJECTION: dosage_g/1000 × max(p1,p2) — 每锅同量
 * 熟制/kg(全量) = Σ COOKING ∧ countInSeasoning: dosage_g/1000 × max(p1,p2)
 * 注射总 = R注射 × 注射/kg
 * 熟制总 = Σ_i 锅i原料 × 熟制/kg(全量) × (i==0 ? 1 : ratio)
 *
 * <p>BOM 统管配方+锅序合并 (2026-06-24): 算法一字不改, 改为读 {@link SeasoningLine} 契约,
 * 让同一套计算同时服务 SP-A {@link RecipeIngredient}(旧源) 与
 * {@link com.cretas.aims.entity.bom.BomSeasoningItem}(BOM 折叠后新源). 零回归.
 */
public final class RecipeCostCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal G_PER_KG = new BigDecimal("1000");

    private RecipeCostCalculator() {}

    /**
     * SP-A 兼容入口 (ProductRecipe + RecipeIngredient). 委托给 {@link #compute(BigDecimal, List, BigDecimal, List)}.
     *
     * @param recipe         配方头(读 subsequentPotRatio)
     * @param ingredients    全部明细(注射段+熟制段)
     * @param injectionRawKg 注射前生料投入重(kg) — Spec §3.2 R注射
     * @param potRawKgs      逐锅熟制原料(kg), size=锅数N; 第1个=第一锅
     */
    public static SeasoningCost compute(ProductRecipe recipe,
                                        List<RecipeIngredient> ingredients,
                                        BigDecimal injectionRawKg,
                                        List<BigDecimal> potRawKgs) {
        return compute(recipe == null ? null : recipe.getSubsequentPotRatio(),
                ingredients, injectionRawKg, potRawKgs);
    }

    /**
     * 核心计算 (数据源中性). BOM 折叠后从 {@code BomRecipe.subsequentPotRatio} + {@code List<BomSeasoningItem>} 走此入口.
     *
     * @param subsequentPotRatio 第二锅起比例 (null → {@link ProductRecipe#DEFAULT_SUBSEQUENT_POT_RATIO})
     * @param ingredients        调料明细 (注射段+熟制段), 任意 {@link SeasoningLine} 实现
     * @param injectionRawKg     注射前生料投入重(kg)
     * @param potRawKgs          逐锅熟制原料(kg), size=锅数N; 第1个=第一锅
     */
    public static SeasoningCost compute(BigDecimal subsequentPotRatio,
                                        List<? extends SeasoningLine> ingredients,
                                        BigDecimal injectionRawKg,
                                        List<BigDecimal> potRawKgs) {
        BigDecimal injPerKg = perKg(ingredients, RecipeIngredient.SECTION_INJECTION, false);
        BigDecimal cookPerKg = perKg(ingredients, RecipeIngredient.SECTION_COOKING, true);

        BigDecimal injectionTotal = nz(injectionRawKg).multiply(injPerKg)
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal ratio = subsequentPotRatio == null
                ? ProductRecipe.DEFAULT_SUBSEQUENT_POT_RATIO : subsequentPotRatio;

        BigDecimal cookingTotal = BigDecimal.ZERO;
        if (potRawKgs != null) {
            for (int i = 0; i < potRawKgs.size(); i++) {
                BigDecimal potFactor = (i == 0) ? BigDecimal.ONE : ratio;
                BigDecimal potCost = nz(potRawKgs.get(i)).multiply(cookPerKg).multiply(potFactor)
                        .setScale(SCALE, RoundingMode.HALF_UP);
                cookingTotal = cookingTotal.add(potCost);
            }
        }
        cookingTotal = cookingTotal.setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal total = injectionTotal.add(cookingTotal).setScale(SCALE, RoundingMode.HALF_UP);
        return new SeasoningCost(injPerKg, cookPerKg, injectionTotal, cookingTotal, total);
    }

    /** Σ section 明细: dosage_g/1000 × max(p1,p2); applyCountInSeasoning 时跳过 countInSeasoning=false 的行. */
    private static BigDecimal perKg(List<? extends SeasoningLine> ingredients,
                                    String section, boolean applyCountInSeasoning) {
        BigDecimal sum = BigDecimal.ZERO;
        if (ingredients == null) return sum.setScale(SCALE, RoundingMode.HALF_UP);
        for (SeasoningLine ing : ingredients) {
            if (!section.equals(ing.getSection())) continue;
            if (applyCountInSeasoning && !Boolean.TRUE.equals(ing.getCountInSeasoning())) continue;
            BigDecimal dosageKgPerKg = nz(ing.getDosagePerKgG()).divide(G_PER_KG, 8, RoundingMode.HALF_UP);
            sum = sum.add(dosageKgPerKg.multiply(maxPrice(ing)));
        }
        return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxPrice(SeasoningLine ing) {
        BigDecimal p1 = ing.getPriceSource1();
        BigDecimal p2 = ing.getPriceSource2();
        if (p1 == null && p2 == null) return BigDecimal.ZERO;
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        return p1.max(p2);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
