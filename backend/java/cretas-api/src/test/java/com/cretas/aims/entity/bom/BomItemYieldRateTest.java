package com.cretas.aims.entity.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D2 (2026-05-10 客户对接会议) BOM 算法 — 出成率自动算原料用量
 *
 * 公式: actualQuantity = standardQuantity / (yieldRate / 100)
 *
 * 客户场景: 成品 200g + 出成率 58% → 自动算原料 344.83g
 */
@DisplayName("D2: BomRecipeItem.getActualQuantity (yieldRate 自动换算)")
class BomItemYieldRateTest {

    @Test
    @DisplayName("出成率 58% + 成品 200g → 原料 344.83g (客户对接会议案例)")
    void customerMeetingExample() {
        BomRecipeItem item = new BomRecipeItem();
        item.setStandardQuantity(new BigDecimal("200"));
        item.setYieldRate(new BigDecimal("58"));

        BigDecimal actual = item.getActualQuantity();

        // 200 / (58 / 100) = 200 / 0.58 = 344.827586...
        // 精度按 BomRecipeItem.getActualQuantity 实现的 6 位小数 HALF_UP
        assertEquals(0, actual.compareTo(new BigDecimal("344.827586")),
                "200g 成品 / 58% 出成率 ≈ 344.83g 原料");
    }

    @Test
    @DisplayName("出成率 100% → 实际用量 = 标准用量 (1:1)")
    void noLossCase() {
        BomRecipeItem item = new BomRecipeItem();
        item.setStandardQuantity(new BigDecimal("100"));
        item.setYieldRate(new BigDecimal("100"));

        BigDecimal actual = item.getActualQuantity();

        assertEquals(0, actual.compareTo(new BigDecimal("100")),
                "出成率 100% 时, 原料用量 = 标准用量");
    }

    @Test
    @DisplayName("出成率 = 0 时回退到标准用量 (避免除以 0)")
    void zeroYieldRateFallback() {
        BomRecipeItem item = new BomRecipeItem();
        item.setStandardQuantity(new BigDecimal("100"));
        item.setYieldRate(BigDecimal.ZERO);

        BigDecimal actual = item.getActualQuantity();

        assertEquals(0, actual.compareTo(new BigDecimal("100")),
                "yieldRate=0 应回退到 standardQuantity 避免除零");
    }

    @Test
    @DisplayName("yieldRate=null 时回退到标准用量 (容错)")
    void nullYieldRateFallback() {
        BomRecipeItem item = new BomRecipeItem();
        item.setStandardQuantity(new BigDecimal("100"));
        item.setYieldRate(null);

        BigDecimal actual = item.getActualQuantity();

        assertEquals(0, actual.compareTo(new BigDecimal("100")),
                "yieldRate=null 应回退到 standardQuantity");
    }

    @Test
    @DisplayName("calculateCost: 出成率 + 单价 → 单项成本")
    void calculateCostWithYield() {
        BomRecipeItem item = new BomRecipeItem();
        item.setStandardQuantity(new BigDecimal("200"));   // 成品 200g
        item.setYieldRate(new BigDecimal("58"));            // 58% 出成率
        item.setUnitPrice(new BigDecimal("0.05"));          // 0.05 元/g

        BigDecimal cost = item.computeItemCost();

        // 实际用量 344.827586g × 0.05 元/g ≈ 17.2414 元
        // 按 calculateCost 实现 setScale(4, HALF_UP)
        assertEquals(0, cost.compareTo(new BigDecimal("17.2414")),
                "成本 = 实际用量 × 单价 (4位小数)");
    }
}
