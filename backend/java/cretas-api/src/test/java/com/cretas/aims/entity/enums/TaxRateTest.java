package com.cretas.aims.entity.enums;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP4-A8 TaxRate 枚举单元测试.
 * <p>覆盖: preTaxPrice/withTaxPrice 精度 (HALF_UP, scale=4) + null 安全。</p>
 */
class TaxRateTest {

    // ==================== TAX_9 (9%) ====================

    @Test
    void taxRate9_preTaxPrice_roundsHalfUp() {
        // 含税 109.00 / 1.09 = 100.000000... → 100.0000
        BigDecimal result = TaxRate.TAX_9.preTaxPrice(new BigDecimal("109.00"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    void taxRate9_withTaxPrice_roundsHalfUp() {
        // 未税 100.00 * 1.09 = 109.0000
        BigDecimal result = TaxRate.TAX_9.withTaxPrice(new BigDecimal("100.00"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("109.0000"));
    }

    @Test
    void taxRate9_preTaxPrice_scale4() {
        // 含税 100.00 / 1.09 = 91.7431... → HALF_UP → 91.7431
        BigDecimal result = TaxRate.TAX_9.preTaxPrice(new BigDecimal("100.00"));
        assertThat(result.scale()).isEqualTo(4);
        assertThat(result).isEqualByComparingTo(new BigDecimal("91.7431"));
    }

    @Test
    void taxRate9_preTaxPrice_fractionRoundsUp() {
        // 含税 10.00 / 1.09 = 9.17431... → 9.1743
        BigDecimal result = TaxRate.TAX_9.preTaxPrice(new BigDecimal("10.00"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("9.1743"));
    }

    // ==================== TAX_13 (13%) ====================

    @Test
    void taxRate13_preTaxPrice_roundsHalfUp() {
        // 含税 113.00 / 1.13 = 100.0000
        BigDecimal result = TaxRate.TAX_13.preTaxPrice(new BigDecimal("113.00"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    void taxRate13_withTaxPrice_roundsHalfUp() {
        // 未税 100.00 * 1.13 = 113.0000
        BigDecimal result = TaxRate.TAX_13.withTaxPrice(new BigDecimal("100.00"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("113.0000"));
    }

    @Test
    void taxRate13_preTaxPrice_scale4() {
        // 含税 100.00 / 1.13 = 88.4956... → HALF_UP → 88.4956
        BigDecimal result = TaxRate.TAX_13.preTaxPrice(new BigDecimal("100.00"));
        assertThat(result.scale()).isEqualTo(4);
        assertThat(result).isEqualByComparingTo(new BigDecimal("88.4956"));
    }

    @Test
    void taxRate13_withTaxPrice_scale4() {
        // 未税 88.4956 * 1.13 = 99.99..., HALF_UP 4 scale
        BigDecimal result = TaxRate.TAX_13.withTaxPrice(new BigDecimal("88.4956"));
        assertThat(result.scale()).isEqualTo(4);
    }

    // ==================== 可逆性 (round-trip 近似) ====================

    @Test
    void taxRate9_roundTrip_preTaxThenWithTax() {
        // 含税 → 未税 → 含税 (允许精度误差 ≤ 0.0001)
        BigDecimal original = new BigDecimal("1000.00");
        BigDecimal preTax = TaxRate.TAX_9.preTaxPrice(original);
        BigDecimal backToTax = TaxRate.TAX_9.withTaxPrice(preTax);
        assertThat(backToTax.subtract(original).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.0001"));
    }

    @Test
    void taxRate13_roundTrip_preTaxThenWithTax() {
        BigDecimal original = new BigDecimal("500.00");
        BigDecimal preTax = TaxRate.TAX_13.preTaxPrice(original);
        BigDecimal backToTax = TaxRate.TAX_13.withTaxPrice(preTax);
        assertThat(backToTax.subtract(original).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.0001"));
    }

    // ==================== null 安全 ====================

    @Test
    void preTaxPrice_null_returnsNull() {
        assertThat(TaxRate.TAX_9.preTaxPrice(null)).isNull();
        assertThat(TaxRate.TAX_13.preTaxPrice(null)).isNull();
    }

    @Test
    void withTaxPrice_null_returnsNull() {
        assertThat(TaxRate.TAX_9.withTaxPrice(null)).isNull();
        assertThat(TaxRate.TAX_13.withTaxPrice(null)).isNull();
    }

    // ==================== 枚举属性 ====================

    @Test
    void taxRate9_getRate() {
        assertThat(TaxRate.TAX_9.getRate()).isEqualByComparingTo(new BigDecimal("0.09"));
    }

    @Test
    void taxRate13_getRate() {
        assertThat(TaxRate.TAX_13.getRate()).isEqualByComparingTo(new BigDecimal("0.13"));
    }
}
