package com.cretas.aims.service.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 🔴 C1 (2026-07-05): FgQuantityUnitConverter — 成品(FG)批次跨单位换算。
 *
 * <p>F006 现场确认: 同一产品的成品批次可能分别记录为 kg (小结填了 productWeight) 与 盒 (未填,
 * 沿用计数单位)。本测试锁定换算方向 + 诚实 null 边界。
 */
@DisplayName("FgQuantityUnitConverter — FG 批次跨单位换算")
class FgQuantityUnitConverterTest {

    @Test
    @DisplayName("同单位(字符串相等) → 原样返回, 不换算")
    void sameUnit_returnsAsIs() {
        BigDecimal qty = new BigDecimal("4454.5");
        assertEquals(0, qty.compareTo(FgQuantityUnitConverter.convert(qty, "盒", "盒", null)));
        assertEquals(0, new BigDecimal("0.45").compareTo(
                FgQuantityUnitConverter.convert(new BigDecimal("0.45"), "kg", "kg", null)));
    }

    @Test
    @DisplayName("计数单位(盒) → kg: qty × gramsPerUnit / 1000")
    void countToKg() {
        // 1 盒 = 120g → 10 盒 = 1.2kg
        BigDecimal result = FgQuantityUnitConverter.convert(new BigDecimal("10"), "盒", "kg", new BigDecimal("120"));
        assertEquals(0, new BigDecimal("1.2000").compareTo(result));
    }

    @Test
    @DisplayName("kg → 计数单位(盒): qty × 1000 / gramsPerUnit")
    void kgToCount() {
        // gramsPerUnit=120g/盒 → 0.45kg = 450g / 120g = 3.75 盒
        BigDecimal result = FgQuantityUnitConverter.convert(new BigDecimal("0.45"), "kg", "盒", new BigDecimal("120"));
        assertEquals(0, new BigDecimal("3.75").compareTo(result));
    }

    @Test
    @DisplayName("🔴 现场复现: 0.45kg 批次 + 4454.5盒 批次同产品 → 各自换算到统一单位后才可加总")
    void liveConfirmedMixedUnitScenario() {
        // 每盒 15g (F006 real product 例) — 4454.5 盒 = 66.8175 kg
        BigDecimal gramsPerUnit = new BigDecimal("15");
        BigDecimal boxBatchInKg = FgQuantityUnitConverter.convert(new BigDecimal("4454.5"), "盒", "kg", gramsPerUnit);
        BigDecimal kgBatch = new BigDecimal("0.45");
        BigDecimal totalKg = boxBatchInKg.add(kgBatch);
        assertEquals(0, new BigDecimal("66.8175").compareTo(boxBatchInKg));
        assertEquals(0, new BigDecimal("67.2675").compareTo(totalKg));
    }

    @Test
    @DisplayName("换算需要但 gramsPerUnit 缺失 → 诚实 null, 不臆造")
    void missingGramsPerUnit_returnsNull() {
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), "盒", "kg", null));
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), "kg", "盒", BigDecimal.ZERO));
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), "kg", "盒", new BigDecimal("-5")));
    }

    @Test
    @DisplayName("双方都是计数单位但字符串不同(盒 vs 个) → 换算系数未知, 诚实 null")
    void bothCountDifferentStrings_returnsNull() {
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), "盒", "个", new BigDecimal("120")));
    }

    @Test
    @DisplayName("双方都非计数单位但字符串不同(kg vs 斤) → 换算系数未知, 诚实 null")
    void bothNonCountDifferentStrings_returnsNull() {
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), "kg", "斤", new BigDecimal("120")));
    }

    @Test
    @DisplayName("qty 为 null → 返回 null")
    void nullQty_returnsNull() {
        assertNull(FgQuantityUnitConverter.convert(null, "盒", "kg", new BigDecimal("120")));
    }

    @Test
    @DisplayName("单位为 null 且不相等 → 诚实 null (不可判断是否需换算)")
    void nullUnit_notEqual_returnsNull() {
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), null, "kg", new BigDecimal("120")));
        assertNull(FgQuantityUnitConverter.convert(new BigDecimal("10"), "kg", null, new BigDecimal("120")));
    }

    @Test
    @DisplayName("单位都为 null → 视为相等, 原样返回 (向后兼容旧数据缺 unit 的场景)")
    void bothNullUnit_returnsAsIs() {
        BigDecimal qty = new BigDecimal("10");
        assertEquals(0, qty.compareTo(FgQuantityUnitConverter.convert(qty, null, null, null)));
    }
}
