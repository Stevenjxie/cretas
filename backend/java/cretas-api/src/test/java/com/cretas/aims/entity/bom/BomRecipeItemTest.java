package com.cretas.aims.entity.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression-pinning tests for {@link BomRecipeItem}'s cost formula.
 *
 * <p>No test previously covered {@code calculateActualQuantity()} /
 * {@code computeItemCost()}. These tests pin the EXACT current behaviour so the
 * #57 refactor (delegating the math to
 * {@link com.cretas.aims.service.shared.CostRollupUtil}) cannot silently change
 * any rounding. If the refactor changes a value, one of these fails.
 *
 * <p>Factory yieldRate is stored as a percent (0–100). The formula is
 * {@code stdQty / (yieldRate/100)} with the {@code yieldRate/100} ratio rounded
 * to scale 6 HALF_UP first, then the division also scale 6 HALF_UP.
 */
class BomRecipeItemTest {

    private static BomRecipeItem item(String stdQty, String yieldRatePercent, String unitPrice) {
        BomRecipeItem it = new BomRecipeItem();
        it.setStandardQuantity(stdQty == null ? null : new BigDecimal(stdQty));
        it.setYieldRate(yieldRatePercent == null ? null : new BigDecimal(yieldRatePercent));
        it.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return it;
    }

    @Test
    @DisplayName("calculateActualQuantity: 200 / (58/100) = 344.827586 scale 6")
    void actualQuantity_withYield() {
        // 58/100 = 0.580000 (scale 6); 200 / 0.580000 = 344.827586 (scale 6 HALF_UP)
        assertEquals(new BigDecimal("344.827586"), item("200", "58.00", null).calculateActualQuantity());
    }

    @Test
    @DisplayName("calculateActualQuantity: yieldRate 100 → 200.000000 (goes through scale-6 division, not the null/zero passthrough)")
    void actualQuantity_fullYield() {
        // yieldRate=100 is NOT the null/zero passthrough branch: ratio=1.000000,
        // then 200 / 1.000000 scale 6 HALF_UP = 200.000000 (scale 6).
        assertEquals(new BigDecimal("200.000000"), item("200", "100.00", null).calculateActualQuantity());
    }

    @Test
    @DisplayName("calculateActualQuantity: yieldRate null → passthrough stdQty")
    void actualQuantity_nullYield() {
        assertEquals(new BigDecimal("200"), item("200", null, null).calculateActualQuantity());
    }

    @Test
    @DisplayName("calculateActualQuantity: yieldRate 0 → passthrough stdQty (no div-by-zero)")
    void actualQuantity_zeroYield() {
        assertEquals(new BigDecimal("200"), item("200", "0.00", null).calculateActualQuantity());
    }

    @Test
    @DisplayName("computeItemCost: actualQty × unitPrice scale 4 HALF_UP")
    void itemCost_withPrice() {
        // actual = 200 / 0.58 = 344.827586; × 0.50 = 172.413793 → scale 4 = 172.4138
        assertEquals(new BigDecimal("172.4138"), item("200", "58.00", "0.50").computeItemCost());
    }

    @Test
    @DisplayName("computeItemCost: null unitPrice → null (not 0)")
    void itemCost_nullPrice() {
        assertNull(item("200", "58.00", null).computeItemCost());
    }

    @Test
    @DisplayName("computeItemCost: null standard quantity is uncollected, not zero")
    void itemCost_nullStandardQuantity() {
        assertNull(item(null, "100.00", "10.00").computeItemCost());
    }
}
