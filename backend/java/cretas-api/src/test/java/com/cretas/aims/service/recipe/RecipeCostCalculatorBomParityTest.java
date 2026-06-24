package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U4 零回归证明 (BOM 统管配方+锅序合并, 2026-06-24).
 *
 * <p>把配方折叠进 BOM 后, 调料成本从 {@code product_recipes}/{@code recipe_ingredients} 改读
 * {@code bom_recipes}/{@code bom_seasoning_items}. 算法一字不改 (同一 {@link RecipeCostCalculator}).
 * 本测试证明: <b>同样的数据</b>经两种数据源 ({@link RecipeIngredient} vs {@link BomSeasoningItem})
 * 计算出的 {@link SeasoningCost} <b>逐分等于</b> —— 因此 U3 迁移做到 1:1 字段拷贝后, U4 读路径切换零回归.
 */
@DisplayName("U4 零回归: BOM 调料源 ≡ product_recipes 调料源")
class RecipeCostCalculatorBomParityTest {

    /** 一行调料的中性数据 (同时喂给两种实体). */
    private record Line(String section, String name, String dosageG, String p1, String p2, boolean countIn) {}

    private RecipeIngredient toRi(Line l) {
        RecipeIngredient i = new RecipeIngredient();
        i.setSection(l.section());
        i.setName(l.name());
        i.setDosagePerKgG(new BigDecimal(l.dosageG()));
        i.setPriceSource1(l.p1() == null ? null : new BigDecimal(l.p1()));
        i.setPriceSource2(l.p2() == null ? null : new BigDecimal(l.p2()));
        i.setCountInSeasoning(l.countIn());
        return i;
    }

    private BomSeasoningItem toBsi(Line l) {
        BomSeasoningItem i = new BomSeasoningItem();
        i.setSection(l.section());
        i.setName(l.name());
        i.setDosagePerKgG(new BigDecimal(l.dosageG()));
        i.setPriceSource1(l.p1() == null ? null : new BigDecimal(l.p1()));
        i.setPriceSource2(l.p2() == null ? null : new BigDecimal(l.p2()));
        i.setCountInSeasoning(l.countIn());
        return i;
    }

    private void assertSame(SeasoningCost a, SeasoningCost b) {
        assertEquals(a.getInjectionCostPerKg(), b.getInjectionCostPerKg(), "injectionPerKg");
        assertEquals(a.getCookingFullCostPerKg(), b.getCookingFullCostPerKg(), "cookingFullPerKg");
        assertEquals(a.getInjectionTotal(), b.getInjectionTotal(), "injectionTotal");
        assertEquals(a.getCookingTotal(), b.getCookingTotal(), "cookingTotal");
        assertEquals(a.getTotal(), b.getTotal(), "total");
    }

    /** 真实风格: 2 注射 + 3 熟制(含1老汤) + 3 锅, ratio 0.3333. 两源逐分一致. */
    @Test
    @DisplayName("多料多锅: RecipeIngredient 与 BomSeasoningItem 结果逐分一致")
    void multiIngredientMultiPot_parity() {
        List<Line> data = List.of(
                new Line("INJECTION", "盐水", "1200", "3", "5", true),
                new Line("INJECTION", "磷酸盐", "300", "12", null, true),
                new Line("COOKING", "卤料包", "800", "8", "9", true),
                new Line("COOKING", "酱油", "500", "4", null, true),
                new Line("COOKING", "老汤", "2000", "99", null, false)); // 不计入

        List<RecipeIngredient> ris = new ArrayList<>();
        List<BomSeasoningItem> bsis = new ArrayList<>();
        for (Line l : data) { ris.add(toRi(l)); bsis.add(toBsi(l)); }

        BigDecimal ratio = new BigDecimal("0.3333");
        BigDecimal injectionRawKg = new BigDecimal("307");
        List<BigDecimal> pots = List.of(new BigDecimal("160"), new BigDecimal("80"), new BigDecimal("80"));

        // 旧源 (ProductRecipe overload, 与生产代码 SP-A 路径一致)
        ProductRecipe pr = new ProductRecipe();
        pr.setSubsequentPotRatio(ratio);
        SeasoningCost viaRecipe = RecipeCostCalculator.compute(pr, ris, injectionRawKg, pots);

        // 新源 (BOM ratio + BomSeasoningItem, 与 U4 BOM 读路径一致)
        SeasoningCost viaBom = RecipeCostCalculator.compute(ratio, bsis, injectionRawKg, pots);

        assertSame(viaRecipe, viaBom);
    }

    /** ratio=null 回退 DEFAULT 在两源下一致 (BomRecipe.subsequentPotRatio 可空). */
    @Test
    @DisplayName("ratio=null 回退 DEFAULT 两源一致")
    void nullRatio_defaultParity() {
        List<Line> data = List.of(new Line("COOKING", "料", "1000", "3", null, true));
        SeasoningCost viaRecipe = RecipeCostCalculator.compute(
                new ProductRecipe(), List.of(toRi(data.get(0))), BigDecimal.ZERO,
                List.of(new BigDecimal("80"), new BigDecimal("80")));
        SeasoningCost viaBom = RecipeCostCalculator.compute(
                (BigDecimal) null, List.of(toBsi(data.get(0))), BigDecimal.ZERO,
                List.of(new BigDecimal("80"), new BigDecimal("80")));
        assertSame(viaRecipe, viaBom);
        // DEFAULT=0.3333 → 80×3×1 + 80×3×0.3333 = 240 + 79.9920 = 319.9920
        assertEquals(new BigDecimal("319.9920"), viaBom.getCookingTotal());
    }
}
