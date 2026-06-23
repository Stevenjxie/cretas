package com.cretas.aims.entity.recipe;

import java.math.BigDecimal;

/**
 * 调料明细的最小读契约 — 让 {@link com.cretas.aims.service.recipe.RecipeCostCalculator}
 * 用同一套算法读取 {@link RecipeIngredient} (SP-A 旧源) 和
 * {@link com.cretas.aims.entity.bom.BomSeasoningItem} (BOM 折叠后新源).
 *
 * <p>BOM 统管配方+锅序合并 (2026-06-24): 成本算法一字不改, 只换数据源.
 * 两个实体字段同形 (section / dosagePerKgG / priceSource1 / priceSource2 / countInSeasoning),
 * 都 implements 本接口 → {@code RecipeCostCalculator} 接受 {@code List<? extends SeasoningLine>}.
 *
 * @since 2026-06-24
 */
public interface SeasoningLine {

    /** 段: {@link RecipeIngredient#SECTION_INJECTION} | {@link RecipeIngredient#SECTION_COOKING}. */
    String getSection();

    /** 每 kg 原料用量 (g). */
    BigDecimal getDosagePerKgG();

    BigDecimal getPriceSource1();

    BigDecimal getPriceSource2();

    /** 老汤/高汤 = false (熟制段不计入调料成本). */
    Boolean getCountInSeasoning();
}
