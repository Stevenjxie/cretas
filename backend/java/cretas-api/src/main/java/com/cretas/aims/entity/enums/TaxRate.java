package com.cretas.aims.entity.enums;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SP4-A8: 税率枚举 — 支持 9%(农产品) / 13%(一般货物) 两档。
 *
 * <p>含税 ↔ 未税自动换算 (scale=4, HALF_UP):</p>
 * <ul>
 *   <li>{@link #preTaxPrice(BigDecimal)} — 含税价 → 未税价</li>
 *   <li>{@link #withTaxPrice(BigDecimal)} — 未税价 → 含税价</li>
 * </ul>
 *
 * <p>成本引擎 (SP5) 用未税价核算; SP4 只加字段 + 换算方法。</p>
 */
public enum TaxRate {

    /** 9% — 农产品适用税率 */
    TAX_9(new BigDecimal("0.09"), "9%", "农产品"),

    /** 13% — 一般货物标准税率 */
    TAX_13(new BigDecimal("0.13"), "13%", "一般货物");

    private final BigDecimal rate;
    private final String displayRate;
    private final String description;

    TaxRate(BigDecimal rate, String displayRate, String description) {
        this.rate = rate;
        this.displayRate = displayRate;
        this.description = description;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getDisplayRate() {
        return displayRate;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 含税价 → 未税价: preTax = taxIncluded / (1 + rate)
     *
     * @param taxIncludedPrice 含税单价 (必须 > 0)
     * @return 未税单价, scale=4, HALF_UP
     */
    public BigDecimal preTaxPrice(BigDecimal taxIncludedPrice) {
        if (taxIncludedPrice == null) {
            return null;
        }
        return taxIncludedPrice.divide(BigDecimal.ONE.add(rate), 4, RoundingMode.HALF_UP);
    }

    /**
     * 未税价 → 含税价: withTax = preTax * (1 + rate)
     *
     * @param preTaxPrice 未税单价
     * @return 含税单价, scale=4, HALF_UP
     */
    public BigDecimal withTaxPrice(BigDecimal preTaxPrice) {
        if (preTaxPrice == null) {
            return null;
        }
        return preTaxPrice.multiply(BigDecimal.ONE.add(rate)).setScale(4, RoundingMode.HALF_UP);
    }
}
