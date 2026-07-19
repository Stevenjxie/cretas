package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.bom.BomSeasoningItem;

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
 * <p>运行时只读取 {@link BomSeasoningItem}，不再保留旧配方数据源或兼容入口。
 */
public final class RecipeCostCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal G_PER_KG = new BigDecimal("1000");
    private static final BigDecimal DEFAULT_SUBSEQUENT_POT_RATIO = new BigDecimal("0.3333");

    private RecipeCostCalculator() {}

    /**
     * 核心计算 (数据源中性). BOM 折叠后从 {@code BomRecipe.subsequentPotRatio} + {@code List<BomSeasoningItem>} 走此入口.
     *
     * @param subsequentPotRatio 第二锅起比例（null → 0.3333）
     * @param ingredients        BOM 调料明细（注射段+熟制段）
     * @param injectionRawKg     注射前生料投入重(kg)
     * @param potRawKgs          逐锅熟制原料(kg), size=锅数N; 第1个=第一锅
     */
    public static SeasoningCost compute(BigDecimal subsequentPotRatio,
                                         List<BomSeasoningItem> ingredients,
                                        BigDecimal injectionRawKg,
                                        List<BigDecimal> potRawKgs) {
        BigDecimal injPerKg = perKg(ingredients, BomSeasoningItem.SECTION_INJECTION, false);
        BigDecimal cookPerKg = perKg(ingredients, BomSeasoningItem.SECTION_COOKING, true);

        BigDecimal injectionTotal = nz(injectionRawKg).multiply(injPerKg)
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal ratio = subsequentPotRatio == null
                ? DEFAULT_SUBSEQUENT_POT_RATIO : subsequentPotRatio;

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

    /**
     * Calculate cooking cost with a pot rule attached to each BOM seasoning binding.
     * The first pot is always 100%; every later pot uses the same (non-compounding)
     * binding ratio. A modern binding with a null ratio is applied to the full process
     * input once. Only historical rows without a material binding may use the legacy
     * process-level ratio as a compatibility fallback.
     */
    public static SeasoningCost computeBindingPotRules(BigDecimal legacyProcessRatio,
                                                       List<BomSeasoningItem> ingredients,
                                                       List<BigDecimal> potRawKgs) {
        BigDecimal cookPerKg = perKg(ingredients, BomSeasoningItem.SECTION_COOKING, true);
        BigDecimal totalRawKg = BigDecimal.ZERO;
        if (potRawKgs != null) {
            for (BigDecimal potRawKg : potRawKgs) {
                totalRawKg = totalRawKg.add(nz(potRawKg));
            }
        }
        int potCount = potRawKgs == null || potRawKgs.isEmpty() ? 0 : potRawKgs.size();
        BigDecimal equalPotRawKg = potCount == 0
                ? BigDecimal.ZERO
                : totalRawKg.divide(BigDecimal.valueOf(potCount), 8, RoundingMode.HALF_UP);

        BigDecimal cookingTotal = BigDecimal.ZERO;
        if (ingredients != null) {
            for (BomSeasoningItem item : ingredients) {
                if (!BomSeasoningItem.SECTION_COOKING.equals(item.getSection())
                        || !Boolean.TRUE.equals(item.getCountInSeasoning())) {
                    continue;
                }
                BigDecimal ratio = item.getSubsequentPotRatio();
                if (ratio == null && item.getMaterialTypeId() == null) {
                    ratio = legacyProcessRatio;
                }
                BigDecimal effectiveRawKg = totalRawKg;
                if (ratio != null && potCount > 0) {
                    BigDecimal factor = BigDecimal.ONE.add(
                            BigDecimal.valueOf(potCount - 1L).multiply(ratio));
                    effectiveRawKg = equalPotRawKg.multiply(factor);
                }
                BigDecimal itemPerKg = nz(item.getDosagePerKgG())
                        .divide(G_PER_KG, 8, RoundingMode.HALF_UP)
                        .multiply(maxPrice(item));
                cookingTotal = cookingTotal.add(effectiveRawKg.multiply(itemPerKg));
            }
        }
        cookingTotal = cookingTotal.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        return new SeasoningCost(zero, cookPerKg, zero, cookingTotal, cookingTotal);
    }

    /** Σ section 明细: dosage_g/1000 × max(p1,p2); applyCountInSeasoning 时跳过 countInSeasoning=false 的行. */
    private static BigDecimal perKg(List<BomSeasoningItem> ingredients,
                                     String section, boolean applyCountInSeasoning) {
        BigDecimal sum = BigDecimal.ZERO;
        if (ingredients == null) return sum.setScale(SCALE, RoundingMode.HALF_UP);
        for (BomSeasoningItem ing : ingredients) {
            if (!section.equals(ing.getSection())) continue;
            if (applyCountInSeasoning && !Boolean.TRUE.equals(ing.getCountInSeasoning())) continue;
            BigDecimal dosageKgPerKg = nz(ing.getDosagePerKgG()).divide(G_PER_KG, 8, RoundingMode.HALF_UP);
            sum = sum.add(dosageKgPerKg.multiply(maxPrice(ing)));
        }
        return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxPrice(BomSeasoningItem ing) {
        BigDecimal p1 = ing.getPriceSource1();
        BigDecimal p2 = ing.getPriceSource2();
        if (p1 == null && p2 == null) return BigDecimal.ZERO;
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        return p1.max(p2);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
