package com.cretas.aims.service.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Single source of truth for the cost-rollup arithmetic shared by the factory
 * BOM domain ({@link com.cretas.aims.entity.bom.BomRecipeItem}) and the
 * restaurant dish-cost domain
 * ({@link com.cretas.aims.service.restaurant.DishCostCardService}).
 *
 * <p>Both domains historically duplicated the yield-折算 / item-cost / total-sum
 * formulas. Duplication risks divergence (different rounding, different
 * null-guards). Centralising the math here guarantees factory and restaurant
 * cannot drift apart — the rounding scale and the null-guard are defined
 * exactly once.
 *
 * <h2>Formulas (mirroring the original factory implementation)</h2>
 * <ul>
 *   <li>{@link #calcActualQuantity(BigDecimal, BigDecimal)} —
 *       {@code actualQty = stdQty / yieldFraction}, scale 6 HALF_UP. A null or
 *       zero {@code yieldFraction} (and a null {@code stdQty}) short-circuit to
 *       passthrough / null, never a divide-by-zero.</li>
 *   <li>{@link #calcItemCost(BigDecimal, BigDecimal)} —
 *       {@code itemCost = actualQty × unitPrice}, scale 4 HALF_UP. A null
 *       {@code unitPrice} (e.g. after RBAC price-stripping) returns {@code null},
 *       NOT {@code 0} — to avoid misleadingly showing a ¥0 cost.</li>
 *   <li>{@link #sumItemCosts(List)} — sum of item costs, scale 4 HALF_UP. If
 *       ANY element is {@code null} the whole total is {@code null}, mirroring
 *       {@code BomRecipeServiceImpl.recomputeMaterialCost} (a partially-priced
 *       recipe has no trustworthy total).</li>
 * </ul>
 *
 * <p><b>Yield convention</b>: this util takes a <em>fraction</em> (0.0–1.0).
 * The factory entity stores {@code yieldRate} as a percent (0–100) and MUST
 * normalise ({@code yieldRate / 100}) before calling. The restaurant
 * {@code Recipe.netYieldRate} is already a fraction and is passed directly.
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57 — dish cost card)
 */
public final class CostRollupUtil {

    /** Cost rounding scale (item cost + totals). */
    public static final int COST_SCALE = 4;
    /** Quantity rounding scale (yield-折算 actual quantity). */
    public static final int QTY_SCALE = 6;

    private CostRollupUtil() {
        // static utility — no instances
    }

    /**
     * 折算实际用量: {@code stdQty / yieldFraction}, scale 6 HALF_UP.
     *
     * <p>Matches {@code BomRecipeItem.calculateActualQuantity()}: a null or zero
     * {@code yieldFraction} means "no loss" and returns {@code stdQty}
     * unchanged. A null {@code stdQty} returns {@code null}.
     *
     * @param stdQty        standard (per-portion) quantity
     * @param yieldFraction net yield as a fraction in (0, 1]; null/zero = passthrough
     * @return折算后的实际用量, or {@code null} if {@code stdQty} is null
     */
    public static BigDecimal calcActualQuantity(BigDecimal stdQty, BigDecimal yieldFraction) {
        if (stdQty == null) {
            return null;
        }
        if (yieldFraction == null || yieldFraction.compareTo(BigDecimal.ZERO) == 0) {
            return stdQty;
        }
        return stdQty.divide(yieldFraction, QTY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 单项成本: {@code actualQty × unitPrice}, scale 4 HALF_UP.
     *
     * <p>Matches {@code BomRecipeItem.computeItemCost()}: a null {@code unitPrice}
     * (price stripped for RBAC, or simply not configured) returns {@code null}
     * rather than {@code 0} so a missing price never masquerades as "free".
     *
     * @param actualQty 折算后的实际用量
     * @param unitPrice 原料单价; null → null result
     * @return单项成本, or {@code null} if either input is null
     */
    public static BigDecimal calcItemCost(BigDecimal actualQty, BigDecimal unitPrice) {
        if (unitPrice == null || actualQty == null) {
            return null;
        }
        return actualQty.multiply(unitPrice).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 配方总成本: sum of item costs, scale 4 HALF_UP.
     *
     * <p>Mirrors {@code BomRecipeServiceImpl.recomputeMaterialCost}: if ANY item
     * cost is {@code null} (an un-priced ingredient) the whole total is
     * {@code null} — a partially-priced recipe has no trustworthy total. An
     * empty list sums to {@code 0.0000}. A null list returns {@code null}.
     *
     * @param itemCosts per-item costs (any null → total null)
     * @return总成本, or {@code null} if list is null or contains any null
     */
    public static BigDecimal sumItemCosts(List<BigDecimal> itemCosts) {
        if (itemCosts == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal c : itemCosts) {
            if (c == null) {
                return null;
            }
            total = total.add(c);
        }
        return total.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
}
