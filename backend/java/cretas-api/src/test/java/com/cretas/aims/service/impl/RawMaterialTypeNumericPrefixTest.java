package com.cretas.aims.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP4-T4: NUMERIC_PREFIX_MAP + getNumericPrefix(category) 单元测试.
 *
 * <p>一物一码标签编码前2位为物料类别数字编码，例如：
 * <ul>
 *   <li>肉类   → "01"</li>
 *   <li>禽类   → "02"</li>
 *   <li>水产类 → "03"</li>
 *   <li>海水鱼 → "03"</li>
 *   <li>淡水鱼 → "03"</li>
 *   <li>虾类   → "03"</li>
 *   <li>贝类   → "03"</li>
 *   <li>调料   → "04"</li>
 *   <li>包材   → "05"</li>
 *   <li>原料   → "06"</li>
 *   <li>其他/null → "99"</li>
 * </ul>
 *
 * @since SP4 V20261002_05
 */
@DisplayName("SP4-T4 NUMERIC_PREFIX_MAP / getNumericPrefix()")
class RawMaterialTypeNumericPrefixTest {

    // ──────────────── 已知类别 ────────────────

    @Test
    @DisplayName("T4-N1: 肉类 → 01")
    void meat() {
        assertEquals("01", RawMaterialTypeServiceImpl.getNumericPrefix("肉类"));
    }

    @Test
    @DisplayName("T4-N2: 禽类 → 02")
    void poultry() {
        assertEquals("02", RawMaterialTypeServiceImpl.getNumericPrefix("禽类"));
    }

    @Test
    @DisplayName("T4-N3: 水产类 → 03")
    void aquatic() {
        assertEquals("03", RawMaterialTypeServiceImpl.getNumericPrefix("水产类"));
    }

    @Test
    @DisplayName("T4-N4: 海水鱼 → 03 (水产子类)")
    void seaFish() {
        assertEquals("03", RawMaterialTypeServiceImpl.getNumericPrefix("海水鱼"));
    }

    @Test
    @DisplayName("T4-N5: 淡水鱼 → 03 (水产子类)")
    void freshwaterFish() {
        assertEquals("03", RawMaterialTypeServiceImpl.getNumericPrefix("淡水鱼"));
    }

    @Test
    @DisplayName("T4-N6: 虾类 → 03 (水产子类)")
    void shrimp() {
        assertEquals("03", RawMaterialTypeServiceImpl.getNumericPrefix("虾类"));
    }

    @Test
    @DisplayName("T4-N7: 贝类 → 03 (水产子类)")
    void shellfish() {
        assertEquals("03", RawMaterialTypeServiceImpl.getNumericPrefix("贝类"));
    }

    @Test
    @DisplayName("T4-N8: 调料 → 04")
    void seasoning() {
        assertEquals("04", RawMaterialTypeServiceImpl.getNumericPrefix("调料"));
    }

    @Test
    @DisplayName("T4-N9: 包材 → 05")
    void packaging() {
        assertEquals("05", RawMaterialTypeServiceImpl.getNumericPrefix("包材"));
    }

    @Test
    @DisplayName("T4-N10: 原料 → 06")
    void rawMaterial() {
        assertEquals("06", RawMaterialTypeServiceImpl.getNumericPrefix("原料"));
    }

    // ──────────────── 边界 / 未知 ────────────────

    @Test
    @DisplayName("T4-N11: null → 99 (default/other)")
    void nullCategory() {
        assertEquals("99", RawMaterialTypeServiceImpl.getNumericPrefix(null));
    }

    @Test
    @DisplayName("T4-N12: empty string → 99")
    void emptyCategory() {
        assertEquals("99", RawMaterialTypeServiceImpl.getNumericPrefix(""));
    }

    @Test
    @DisplayName("T4-N13: blank whitespace → 99")
    void blankCategory() {
        assertEquals("99", RawMaterialTypeServiceImpl.getNumericPrefix("  "));
    }

    @Test
    @DisplayName("T4-N14: completely unknown category → 99")
    void unknownCategory() {
        assertEquals("99", RawMaterialTypeServiceImpl.getNumericPrefix("未知物料XYZ"));
    }

    @Test
    @DisplayName("T4-N15: result is always exactly 2 digits")
    void allKnownPrefixesTwoDigits() {
        String[] known = {"肉类","禽类","水产类","海水鱼","淡水鱼","虾类","贝类","调料","包材","原料"};
        for (String cat : known) {
            String prefix = RawMaterialTypeServiceImpl.getNumericPrefix(cat);
            assertEquals(2, prefix.length(),
                    "prefix for '" + cat + "' must be 2 digits, got: " + prefix);
            assertTrue(prefix.matches("\\d{2}"),
                    "prefix for '" + cat + "' must be numeric digits, got: " + prefix);
        }
    }
}
