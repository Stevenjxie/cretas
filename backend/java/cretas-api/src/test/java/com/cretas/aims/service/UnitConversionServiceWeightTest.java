package com.cretas.aims.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R13 (2026-06-22): UnitConversionService 重量单位换算 (斤=0.5kg 全链 kg 基准).
 *
 * <p>验证 斤/吨/g/mg/克/公斤/千克/t 之间的两两换算 + toKg 边界归一 +
 * 非重量单位不归一 (绝不强行当 kg) + null 安全.
 */
@DisplayName("R13 UnitConversionService 重量单位换算")
class UnitConversionServiceWeightTest {

    private UnitConversionService service;

    @BeforeEach
    void setUp() {
        service = com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade();
    }

    // ---------- toKg: 边界归一到 kg 基准 ----------

    @Test
    @DisplayName("斤 → kg: 100斤 = 50kg")
    void toKg_jinToKg() {
        assertThat(service.toKg(new BigDecimal("100"), "斤"))
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    @DisplayName("kg → kg: 透传原值")
    void toKg_kgPassthrough() {
        assertThat(service.toKg(new BigDecimal("42.5"), "kg"))
                .isEqualByComparingTo(new BigDecimal("42.5"));
    }

    @Test
    @DisplayName("g → kg: 1500g = 1.5kg")
    void toKg_gramToKg() {
        assertThat(service.toKg(new BigDecimal("1500"), "g"))
                .isEqualByComparingTo(new BigDecimal("1.5"));
    }

    @Test
    @DisplayName("吨 / t → kg: 2吨 = 2000kg")
    void toKg_tonToKg() {
        assertThat(service.toKg(new BigDecimal("2"), "吨"))
                .isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(service.toKg(new BigDecimal("2"), "t"))
                .isEqualByComparingTo(new BigDecimal("2000"));
    }

    @Test
    @DisplayName("mg → kg: 1000000mg = 1kg")
    void toKg_mgToKg() {
        assertThat(service.toKg(new BigDecimal("1000000"), "mg"))
                .isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    @DisplayName("中文重量别名: 公斤/千克/克 命中")
    void toKg_chineseAliases() {
        assertThat(service.toKg(new BigDecimal("3"), "公斤")).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(service.toKg(new BigDecimal("3"), "千克")).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(service.toKg(new BigDecimal("3000"), "克")).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    @DisplayName("非重量单位 (个/箱/袋/ml/L): toKg 返 null — 绝不强行当 kg")
    void toKg_nonWeightReturnsNull() {
        assertThat(service.toKg(new BigDecimal("10"), "个")).isNull();
        assertThat(service.toKg(new BigDecimal("10"), "箱")).isNull();
        assertThat(service.toKg(new BigDecimal("10"), "袋")).isNull();
        assertThat(service.toKg(new BigDecimal("10"), "ml")).isNull();
        assertThat(service.toKg(new BigDecimal("10"), "L")).isNull();
    }

    @Test
    @DisplayName("toKg null 安全: null 值 / null 单位 → null")
    void toKg_nullSafe() {
        assertThat(service.toKg(null, "斤")).isNull();
        assertThat(service.toKg(new BigDecimal("1"), null)).isNull();
    }

    @Test
    @DisplayName("isWeightUnit: 重量单位 true, 离散/体积单位 false")
    void isWeightUnit() {
        assertThat(service.isWeightUnit("斤")).isTrue();
        assertThat(service.isWeightUnit("KG")).isTrue();   // 大小写不敏感
        assertThat(service.isWeightUnit("吨")).isTrue();
        assertThat(service.isWeightUnit("个")).isFalse();
        assertThat(service.isWeightUnit("ml")).isFalse();
        assertThat(service.isWeightUnit(null)).isFalse();
    }

    // ---------- convert: 任意重量两两换算 ----------

    @Test
    @DisplayName("convert 斤 → kg: 1斤 = 0.5kg")
    void convert_jinToKg() {
        assertThat(service.convert(new BigDecimal("1"), "斤", "kg"))
                .isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("convert kg → 斤: 1kg = 2斤")
    void convert_kgToJin() {
        assertThat(service.convert(new BigDecimal("1"), "kg", "斤"))
                .isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("convert 斤 → g: 1斤 = 500g (跨重量单位经 kg 基准)")
    void convert_jinToGram() {
        assertThat(service.convert(new BigDecimal("1"), "斤", "g"))
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("convert 斤 → 吨: 2000斤 = 1吨")
    void convert_jinToTon() {
        assertThat(service.convert(new BigDecimal("2000"), "斤", "吨"))
                .isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    @DisplayName("convert g↔kg 旧行为保持 (回归): 1000g = 1kg, 1kg = 1000g")
    void convert_gramKgRegression() {
        assertThat(service.convert(new BigDecimal("1000"), "g", "kg"))
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(service.convert(new BigDecimal("1"), "kg", "g"))
                .isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("convert ml↔L 旧行为保持 (回归)")
    void convert_volumeRegression() {
        assertThat(service.convert(new BigDecimal("1000"), "ml", "L"))
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(service.convert(new BigDecimal("1"), "L", "ml"))
                .isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("convert 跨维度 (斤 → ml) / 离散 (个 → kg): 返 null")
    void convert_crossDimensionNull() {
        assertThat(service.convert(new BigDecimal("1"), "斤", "ml")).isNull();
        assertThat(service.convert(new BigDecimal("1"), "个", "kg")).isNull();
    }

    @Test
    @DisplayName("convert 同单位透传, null 安全")
    void convert_sameUnitAndNullSafe() {
        assertThat(service.convert(new BigDecimal("7"), "斤", "斤"))
                .isEqualByComparingTo(new BigDecimal("7"));
        assertThat(service.convert(null, "斤", "kg")).isNull();
        assertThat(service.convert(new BigDecimal("1"), null, "kg")).isNull();
    }

    @Test
    @DisplayName("isSupported: 任意重量对 true, 跨维度 false")
    void isSupported() {
        assertThat(service.isSupported("斤", "kg")).isTrue();
        assertThat(service.isSupported("斤", "g")).isTrue();
        assertThat(service.isSupported("吨", "kg")).isTrue();
        assertThat(service.isSupported("ml", "L")).isTrue();
        assertThat(service.isSupported("斤", "ml")).isFalse();
        assertThat(service.isSupported("个", "kg")).isFalse();
    }
}
