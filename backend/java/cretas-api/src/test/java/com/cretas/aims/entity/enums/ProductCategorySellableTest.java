package com.cretas.aims.entity.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 销售订单商品选择的类别口径。
 *
 * <h2>2026-08-12 Steve 拍板(六膳门张权真机反馈)</h2>
 * 「有啥不能卖的 给钱 我都能卖」——原料、辅料、包材都卖;
 * 「半成品卖 过分了」;结论原话:「出了半成品全开吧」。
 *
 * <h2>这个方法在改之前是死代码</h2>
 * 原实现是白名单(只放行成品/菜品/套餐/代工/客供料), 且<b>零调用点</b> ——
 * 也就是说系统里「可销售」这个概念一直只写在纸上, 实际过滤靠的是别处的
 * {@code <> 'RAW_MATERIAL'}。现在它成了唯一权威, 必须有用例钉住。
 *
 * <p>⚠️ CI 的 Java selector 目前只跑
 * {@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest},
 * <b>不覆盖本用例</b>(本仓 Java 全量套件只在 full_audit 跑)。防回归的主要载体是
 * 前端那条 {@code salesProductEndpoint.source.spec.ts} —— 它会随 web-admin 改动
 * 在 PR 上真跑。
 */
class ProductCategorySellableTest {

    @Test
    @DisplayName("半成品不可售 —— 唯一被排除的类别")
    void semiFinishedIsNotSellable() {
        assertThat(ProductCategory.isSellable(ProductCategory.SEMI_FINISHED)).isFalse();
    }

    @Test
    @DisplayName("原料/辅料(调味料)/包材都可售 —— 这正是本次要放开的")
    void materialsAreSellable() {
        assertThat(ProductCategory.isSellable(ProductCategory.RAW_MATERIAL)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.SEASONING)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.PACKAGING)).isTrue();
    }

    @Test
    @DisplayName("原本就能卖的一个都没少")
    void previouslySellableStaySellable() {
        assertThat(ProductCategory.isSellable(ProductCategory.FINISHED_PRODUCT)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.DISH)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.COMBO)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.CONTRACT_MANUFACTURING)).isTrue();
        assertThat(ProductCategory.isSellable(ProductCategory.CUSTOMER_MATERIAL)).isTrue();
    }

    /**
     * 白名单会让这些历史数据<b>静默消失</b> —— 它们现在能正常下单。
     * F006 实测 {@code product_category} 五个取值都非空, 但别的租户不保证。
     */
    @Test
    @DisplayName("未分类(null/空/空白)可售 —— 黑名单而非白名单的理由")
    void unclassifiedIsSellable() {
        assertThat(ProductCategory.isSellable(null)).isTrue();
        assertThat(ProductCategory.isSellable("")).isTrue();
        assertThat(ProductCategory.isSellable("   ")).isTrue();
    }

    @Test
    @DisplayName("大小写与首尾空白不影响判定(库里是自由文本列)")
    void toleratesCasingAndWhitespace() {
        assertThat(ProductCategory.isSellable("semi_finished")).isFalse();
        assertThat(ProductCategory.isSellable("  SEMI_FINISHED  ")).isFalse();
    }
}
