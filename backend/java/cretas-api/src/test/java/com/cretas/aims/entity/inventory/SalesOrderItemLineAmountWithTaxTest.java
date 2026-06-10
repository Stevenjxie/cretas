package com.cretas.aims.entity.inventory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TDD guard for E-4: SalesOrderItem.getLineAmountWithTax()
 *
 * <p>Mirrors {@code PurchaseOrderItem.getLineAmountWithTax()} (D-11, batch A):
 * <ul>
 *   <li>Same {@code @Transient @PriceSensitive} contract.
 *   <li>Same scale-2 HALF_UP rounding via {@code setScale(2, BigDecimal.ROUND_HALF_UP)}.
 *   <li>Same null-propagation: returns {@code null} when {@code lineAmount==null}
 *       (i.e., when {@code unitPrice} or {@code quantity} is null — covers the
 *       {@link com.cretas.aims.security.PriceFieldResponseAdvice} strip path).
 *   <li>Same zero-tax short-circuit: returns {@code lineAmount} unchanged when
 *       {@code taxRate} is null or zero.
 *   <li>SO-specific: {@code getLineAmount()} already applies {@code discountRate}
 *       before the tax multiplier, so the tax is computed on the discounted base
 *       (consistent with 含税价 = 折后价 × (1 + tax%)).
 * </ul>
 *
 * <p>Precision fixture (taxRate = 9.0):
 * <pre>
 *   quantity=100, unitPrice=40.00 → lineAmount=4000.00
 *   taxMultiplier = 1 + 9/100 = 1.090000 (scale-6 HALF_UP in divide)
 *   lineAmountWithTax = 4000.00 × 1.090000 = 4360.00
 * </pre>
 * Matches D-11 audit data exactly.
 */
class SalesOrderItemLineAmountWithTaxTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private SalesOrderItem item(BigDecimal qty, BigDecimal unitPrice,
                                BigDecimal discountRate, BigDecimal taxRate) {
        SalesOrderItem item = new SalesOrderItem();
        item.setQuantity(qty);
        item.setUnit("盒");
        item.setProductTypeId("PT-TEST");
        item.setProductName("测试品");
        item.setSalesOrderId("SO-TEST");
        item.setUnitPrice(unitPrice);
        item.setDiscountRate(discountRate);
        item.setTaxRate(taxRate);
        return item;
    }

    // ── normal-tax-rate ───────────────────────────────────────────────────────

    /**
     * D-11 mirror: qty=100, unitPrice=40, taxRate=9 → lineAmount=4000 →
     * lineAmountWithTax=4360.
     */
    @Test
    void normalTaxRate9_noDiscount_returnsLineAmountTimesTaxMultiplier() {
        SalesOrderItem soi = item(
                new BigDecimal("100"),
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                new BigDecimal("9.0")
        );
        assertEquals(new BigDecimal("4000.00"), soi.getLineAmount(),
                "lineAmount should be qty × unitPrice = 4000.00");
        assertEquals(new BigDecimal("4360.00"), soi.getLineAmountWithTax(),
                "lineAmountWithTax should be 4000.00 × 1.09 = 4360.00");
    }

    /**
     * Verify that discount is applied to the base BEFORE tax multiplication.
     * qty=100, unitPrice=40, discountRate=10 (10% off) →
     *   lineAmount = 4000 × 0.90 = 3600.00
     *   lineAmountWithTax = 3600.00 × 1.09 = 3924.00
     */
    @Test
    void normalTaxRate9_withDiscount10pct_taxAppliedOnDiscountedBase() {
        SalesOrderItem soi = item(
                new BigDecimal("100"),
                new BigDecimal("40.00"),
                new BigDecimal("10"),   // 10% discount
                new BigDecimal("9.0")
        );
        assertEquals(new BigDecimal("3600.00"), soi.getLineAmount(),
                "discounted lineAmount should be 4000 × 0.90 = 3600.00");
        assertEquals(new BigDecimal("3924.00"), soi.getLineAmountWithTax(),
                "lineAmountWithTax should be 3600.00 × 1.09 = 3924.00");
    }

    // ── zero / null taxRate ───────────────────────────────────────────────────

    /** taxRate=0 → lineAmountWithTax == lineAmount (no tax). */
    @Test
    void zeroTaxRate_returnsLineAmountUnchanged() {
        SalesOrderItem soi = item(
                new BigDecimal("100"),
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
        BigDecimal expected = soi.getLineAmount();
        assertEquals(expected, soi.getLineAmountWithTax(),
                "Zero taxRate must short-circuit to lineAmount");
    }

    /** taxRate=null → lineAmountWithTax == lineAmount (treated as 0%). */
    @Test
    void nullTaxRate_returnsLineAmountUnchanged() {
        SalesOrderItem soi = item(
                new BigDecimal("100"),
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                null   // taxRate not set
        );
        BigDecimal expected = soi.getLineAmount();
        assertEquals(expected, soi.getLineAmountWithTax(),
                "Null taxRate must short-circuit to lineAmount (same as zero)");
    }

    // ── null-propagation (PriceFieldResponseAdvice strip path) ───────────────

    /** unitPrice stripped to null → lineAmount=null → lineAmountWithTax=null (not NPE). */
    @Test
    void nullUnitPrice_returnsNull_notNPE() {
        SalesOrderItem soi = item(
                new BigDecimal("100"),
                null,               // simulates PriceFieldResponseAdvice strip
                BigDecimal.ZERO,
                new BigDecimal("9.0")
        );
        assertNull(soi.getLineAmount(),
                "Stripped unitPrice must yield lineAmount=null");
        assertNull(soi.getLineAmountWithTax(),
                "Null lineAmount must propagate to lineAmountWithTax=null, not NPE");
    }

    /** quantity=null → lineAmount=null → lineAmountWithTax=null (not NPE). */
    @Test
    void nullQuantity_returnsNull_notNPE() {
        SalesOrderItem soi = item(
                null,               // pathological — quantity never stripped, but null-safe
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                new BigDecimal("9.0")
        );
        assertNull(soi.getLineAmount(),
                "Null quantity must yield lineAmount=null");
        assertNull(soi.getLineAmountWithTax(),
                "Null lineAmount must propagate to lineAmountWithTax=null, not NPE");
    }

    // ── fractional tax rate (rounding) ────────────────────────────────────────

    /**
     * taxRate=6.5, qty=1, unitPrice=100.00
     * taxMultiplier = 1 + 6.5/100 = 1.065000
     * lineAmountWithTax = 100.00 × 1.065000 = 106.50  (exact, no rounding needed)
     */
    @Test
    void fractionalTaxRate_roundsCorrectly() {
        SalesOrderItem soi = item(
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                new BigDecimal("6.5")
        );
        assertEquals(new BigDecimal("106.50"), soi.getLineAmountWithTax(),
                "lineAmountWithTax for 6.5% tax on 100.00 should be 106.50");
    }
}
