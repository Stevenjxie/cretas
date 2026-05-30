package com.cretas.aims.service.inventory;

import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression test for the 2026-05-30 pricing bug surfaced by the F006 UI E2E.
 *
 * <p>{@code PricingResult.finalPrice} is a LINE TOTAL ({@code unitPriceList × quantity − discounts}).
 * {@code SalesServiceImpl.createSalesOrder} previously assigned it straight to the per-unit
 * {@code resolvedUnitPrice}, so a 276 × ¥50 line was stored as {@code unitPrice = ¥13,800} and
 * {@code totalAmount = ¥3,808,800 (= 276² × 50)}. {@link SalesServiceImpl#lineTotalToUnitPrice}
 * now converts the line total back to per-unit; these cases pin that contract.
 */
class SalesServiceImplPricingUnitPriceTest {

    @Test
    void lineTotal_dividedByQuantity_yieldsPerUnit() {
        // The exact F006 repro: 276 units, ¥50 each → engine line total ¥13,800.
        BigDecimal lineTotal = new BigDecimal("13800");
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                lineTotal, new BigDecimal("276"), new BigDecimal("50"));
        assertEquals(0, new BigDecimal("50.00").compareTo(perUnit),
                "276 × ¥50 line total ¥13,800 must convert back to per-unit ¥50, not stay ¥13,800");
    }

    @Test
    void discountedLineTotal_convertsToDiscountedPerUnit() {
        // 100 units, list ¥50 = ¥5,000 line total; a 10% strategy discount → ¥4,500 final line total.
        // Per-unit after discount = ¥45.00.
        BigDecimal discountedLineTotal = new BigDecimal("4500");
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                discountedLineTotal, new BigDecimal("100"), new BigDecimal("50"));
        assertEquals(0, new BigDecimal("45.00").compareTo(perUnit));
    }

    @Test
    void nonDivisibleLineTotal_roundsHalfUpToTwoScale() {
        // 3 units, ¥10.00 line total → 3.333... → HALF_UP scale 2 = ¥3.33.
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("10.00"), new BigDecimal("3"), new BigDecimal("3"));
        assertEquals(0, new BigDecimal("3.33").compareTo(perUnit));
    }

    @Test
    void nullLineTotal_returnsFallback() {
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(null, new BigDecimal("10"), fallback));
    }

    @Test
    void zeroQuantity_returnsFallback() {
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("500"), BigDecimal.ZERO, fallback));
    }

    @Test
    void nullQuantity_returnsFallback() {
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("500"), null, fallback));
    }

    @Test
    void zeroLineTotal_freeGoods_convertsToZeroPerUnit() {
        // A 100%-discount promotion: final line total ¥0 → per-unit ¥0.00 (valid, not fallback).
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("50"));
        assertEquals(0, BigDecimal.ZERO.compareTo(perUnit));
    }

    @Test
    void negativeLineTotal_returnsFallback_neverNegativeUnitPrice() {
        // Defensive: a negative line total (should not happen — engine clamps ≥0) must NOT become a
        // negative unit price; fall back to the caller's price instead.
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("-100"), new BigDecimal("10"), fallback));
    }
}
