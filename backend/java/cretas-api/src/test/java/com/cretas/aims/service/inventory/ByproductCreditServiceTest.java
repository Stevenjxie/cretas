package com.cretas.aims.service.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 副产抵扣额的唯一计算入口。
 *
 * <p>🔴 本组最重要的一条是「null ≠ 0」: 单价未确认返回 {@code null}, 而不是 0。
 * 0 会被读成「这批副产不值钱」并当成算完了; null 才如实表达「还没人确认过」。
 * 这与本仓既有的「未归集」是同一套诚实语义。</p>
 */
class ByproductCreditServiceTest {

    @Test
    void creditIsQuantityTimesUnitPrice() {
        assertThat(ByproductCreditService.creditOf(new BigDecimal("3.0"), new BigDecimal("4.00")))
                .isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("禁降级: 未确认单价返回 null 而不是 0")
    void missingUnitPriceYieldsNullNotZero() {
        assertThat(ByproductCreditService.creditOf(new BigDecimal("3.0"), null)).isNull();
    }

    @Test
    @DisplayName("确认为 0 与「没填」必须分得开 —— 0 是一个真实的确认结果")
    void explicitZeroUnitPriceIsAConfirmedCreditOfZero() {
        assertThat(ByproductCreditService.creditOf(new BigDecimal("3.0"), BigDecimal.ZERO))
                .isNotNull()
                .isEqualByComparingTo("0");
    }

    @Test
    void missingQuantityYieldsNull() {
        assertThat(ByproductCreditService.creditOf(null, new BigDecimal("4.00"))).isNull();
    }

    @Test
    @DisplayName("金额保留两位, 与本仓其它金额口径一致")
    void creditIsRoundedToTwoDecimals() {
        // 2.805 × 3.33 = 9.34065 → 9.34
        assertThat(ByproductCreditService.creditOf(new BigDecimal("2.805"), new BigDecimal("3.33")))
                .isEqualByComparingTo("9.34");
    }
}
