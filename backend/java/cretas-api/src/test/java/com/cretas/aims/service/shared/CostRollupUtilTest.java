package com.cretas.aims.service.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link CostRollupUtil}.
 *
 * <p>The util is the single source of truth for the cost-rollup math shared by
 * the factory BOM domain ({@code BomRecipeItem}) and the restaurant dish-cost
 * domain ({@code DishCostCardService}). The expected values are pinned to the
 * existing factory formula:
 * <ul>
 *   <li>{@code calcActualQuantity} = stdQty / yieldFraction, scale 6 HALF_UP
 *       (yieldFraction null/zero → passthrough stdQty)</li>
 *   <li>{@code calcItemCost} = actualQty × unitPrice, scale 4 HALF_UP
 *       (null unitPrice → null, never 0)</li>
 *   <li>{@code sumItemCosts} = sum, scale 4 HALF_UP
 *       (any null element → null, mirroring recomputeMaterialCost guard)</li>
 * </ul>
 *
 * <p>NOTE: the brief's draft expected calcActualQuantity(0.45, 0.58) = 0.776000,
 * but the real factory formula (std/frac scale 6 HALF_UP) yields 0.775862
 * (0.45 / 0.58 = 0.77586206...). We pin to the factory-exact value 0.775862.
 */
class CostRollupUtilTest {

    @Test
    @DisplayName("calcActualQuantity: stdQty / yieldFraction, scale 6 HALF_UP")
    void calcActualQuantity_dividesByFraction() {
        // 0.45 / 0.58 = 0.7758620689... → scale 6 HALF_UP = 0.775862
        assertEquals(new BigDecimal("0.775862"),
                CostRollupUtil.calcActualQuantity(new BigDecimal("0.45"), new BigDecimal("0.58")));
    }

    @Test
    @DisplayName("calcActualQuantity: fraction 1.0 is passthrough (scale 6)")
    void calcActualQuantity_fractionOne() {
        assertEquals(new BigDecimal("0.450000"),
                CostRollupUtil.calcActualQuantity(new BigDecimal("0.45"), new BigDecimal("1.0")));
    }

    @Test
    @DisplayName("calcActualQuantity: null fraction → passthrough stdQty unchanged")
    void calcActualQuantity_nullFraction() {
        assertEquals(new BigDecimal("0.45"),
                CostRollupUtil.calcActualQuantity(new BigDecimal("0.45"), null));
    }

    @Test
    @DisplayName("calcActualQuantity: zero fraction → passthrough stdQty unchanged (no div-by-zero)")
    void calcActualQuantity_zeroFraction() {
        assertEquals(new BigDecimal("0.45"),
                CostRollupUtil.calcActualQuantity(new BigDecimal("0.45"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("calcActualQuantity: null stdQty → null")
    void calcActualQuantity_nullStdQty() {
        assertNull(CostRollupUtil.calcActualQuantity(null, new BigDecimal("0.58")));
    }

    @Test
    @DisplayName("calcItemCost: actualQty × unitPrice, scale 4 HALF_UP")
    void calcItemCost_multiplies() {
        // 0.776 × 15.90 = 12.3384
        assertEquals(new BigDecimal("12.3384"),
                CostRollupUtil.calcItemCost(new BigDecimal("0.776"), new BigDecimal("15.90")));
    }

    @Test
    @DisplayName("calcItemCost: null unitPrice → null (not 0, no-misleading guard)")
    void calcItemCost_nullPrice() {
        assertNull(CostRollupUtil.calcItemCost(new BigDecimal("0.776"), null));
    }

    @Test
    @DisplayName("calcItemCost: null actualQty → null")
    void calcItemCost_nullActual() {
        assertNull(CostRollupUtil.calcItemCost(null, new BigDecimal("15.90")));
    }

    @Test
    @DisplayName("sumItemCosts: any null element → null (mirrors recomputeMaterialCost guard)")
    void sumItemCosts_anyNullIsNull() {
        assertNull(CostRollupUtil.sumItemCosts(
                Arrays.asList(new BigDecimal("12.33"), new BigDecimal("5.67"), null)));
    }

    @Test
    @DisplayName("sumItemCosts: all present → sum scale 4 HALF_UP")
    void sumItemCosts_allPresent() {
        assertEquals(new BigDecimal("18.0000"),
                CostRollupUtil.sumItemCosts(
                        Arrays.asList(new BigDecimal("12.33"), new BigDecimal("5.67"))));
    }

    @Test
    @DisplayName("sumItemCosts: empty list → 0 scale 4 (no items, not null)")
    void sumItemCosts_empty() {
        assertEquals(new BigDecimal("0.0000"), CostRollupUtil.sumItemCosts(Collections.emptyList()));
    }

    @Test
    @DisplayName("sumItemCosts: null list → null")
    void sumItemCosts_nullList() {
        assertNull(CostRollupUtil.sumItemCosts(null));
    }
}
