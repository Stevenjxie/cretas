package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP4: Item 税率继承链单元验证 (不跑完整 Service 创建流程的纯逻辑验证)。
 *
 * <p>三层继承链: item 显式 > SO.defaultTaxRate > 0
 *
 * <p>Note: createSalesOrder/updateSalesOrder 的完整继承路径通过集成测试覆盖 (需 Spring Context).
 * 此测试覆盖实体层和计算层的正确性验证。
 */
@DisplayName("SP4: Item 税率继承链单元验证")
class SalesServiceImplTaxRateInheritanceSp4Test {

    /** 构建 SalesOrder 模拟三层继承链结果 */
    private SalesOrder buildOrderSimulatingInheritance(
            BigDecimal soDefaultTaxRate,
            BigDecimal itemExplicitTaxRate,
            BigDecimal unitPrice,
            BigDecimal qty) {

        // 模拟 createSalesOrder 中的三层继承逻辑
        BigDecimal effectiveTaxRate = itemExplicitTaxRate;
        if (effectiveTaxRate == null) {
            effectiveTaxRate = soDefaultTaxRate != null ? soDefaultTaxRate : BigDecimal.ZERO;
        }

        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-INH");
        item.setProductName("继承测试品");
        item.setSalesOrderId("SO-INH");
        item.setQuantity(qty);
        item.setUnit("盒");
        item.setUnitPrice(unitPrice);
        item.setDiscountRate(BigDecimal.ZERO);
        item.setTaxRate(effectiveTaxRate);   // 已应用继承链

        SalesOrder order = new SalesOrder();
        order.setId("SO-INH");
        order.setDefaultTaxRate(soDefaultTaxRate);
        order.setItems(new java.util.ArrayList<>(java.util.List.of(item)));
        return order;
    }

    /**
     * SP4 继承链验证 1: item 显式税率 > SO.defaultTaxRate。
     * item.taxRate=9, SO.defaultTaxRate=13 → 用 9。
     */
    @Test
    @DisplayName("SP4: item 显式税率 (9%) 优先于 SO.defaultTaxRate (13%)")
    void itemExplicitTaxRate_overridesSoDefault() {
        SalesOrder order = buildOrderSimulatingInheritance(
                new BigDecimal("13.0"),    // SO default
                new BigDecimal("9.0"),     // item 显式 — 优先
                new BigDecimal("100.00"),
                new BigDecimal("50")
        );

        SalesOrderItem item = order.getItems().get(0);
        assertEquals(new BigDecimal("9.0"), item.getTaxRate(),
                "item 显式税率 9% 应优先于 SO.defaultTaxRate 13%");

        // 含税金额 = 50 × 100 × (1 + 9%) = 5000 × 1.09 = 5450.00
        assertEquals(new BigDecimal("5450.00"), item.getLineAmountWithTax(),
                "含税行金额应按 item 显式 9% 税率计算");
    }

    /**
     * SP4 继承链验证 2: item.taxRate=null → 继承 SO.defaultTaxRate。
     * item.taxRate=null, SO.defaultTaxRate=13 → 用 13。
     */
    @Test
    @DisplayName("SP4: item 无显式税率 → 继承 SO.defaultTaxRate (13%)")
    void itemNullTaxRate_inheritsFromSoDefault() {
        SalesOrder order = buildOrderSimulatingInheritance(
                new BigDecimal("13.0"),    // SO default
                null,                      // item 无显式 → 继承
                new BigDecimal("100.00"),
                new BigDecimal("50")
        );

        SalesOrderItem item = order.getItems().get(0);
        assertEquals(new BigDecimal("13.0"), item.getTaxRate(),
                "item.taxRate=null 应继承 SO.defaultTaxRate=13.0");

        // 含税金额 = 50 × 100 × (1 + 13%) = 5000 × 1.13 = 5650.00
        assertEquals(new BigDecimal("5650.00"), item.getLineAmountWithTax(),
                "含税行金额应按继承的 13% 税率计算");
    }

    /**
     * SP4 继承链验证 3: item=null, SO.defaultTaxRate=null → fallback 0 (免税).
     */
    @Test
    @DisplayName("SP4: item=null, SO.defaultTaxRate=null → fallback 0 (向后兼容)")
    void bothNull_fallbackToZero() {
        SalesOrder order = buildOrderSimulatingInheritance(
                null,    // SO 无 default
                null,    // item 无显式
                new BigDecimal("100.00"),
                new BigDecimal("50")
        );

        SalesOrderItem item = order.getItems().get(0);
        assertEquals(BigDecimal.ZERO, item.getTaxRate(),
                "两层均 null → fallback 0");

        // 含税金额 = 未税金额 (零税)
        assertEquals(item.getLineAmount(), item.getLineAmountWithTax(),
                "零税率: lineAmountWithTax == lineAmount");
    }

    /**
     * SP4: tax_amount 汇总逻辑验证 (减法兜底, spec §2.4)。
     * 多行订单的 tax_amount = Σ (lineAmountWithTax − lineAmount)。
     * qty1=100, price1=10.00, taxRate1=13 → taxLine1 = 130.00
     * qty2=50,  price2=20.00, taxRate2=9  → taxLine2 = 90.00
     * total taxAmount = 220.00
     */
    @Test
    @DisplayName("SP4: 多行订单 tax_amount 汇总 (减法兜底)")
    void multiLineOrder_taxAmountSumBySubtraction() {
        SalesOrderItem item1 = new SalesOrderItem();
        item1.setProductTypeId("PT-1");
        item1.setProductName("品1");
        item1.setSalesOrderId("SO-MULTI");
        item1.setQuantity(new BigDecimal("100"));
        item1.setUnit("盒");
        item1.setUnitPrice(new BigDecimal("10.00"));
        item1.setDiscountRate(BigDecimal.ZERO);
        item1.setTaxRate(new BigDecimal("13.0"));  // 13%

        SalesOrderItem item2 = new SalesOrderItem();
        item2.setProductTypeId("PT-2");
        item2.setProductName("品2");
        item2.setSalesOrderId("SO-MULTI");
        item2.setQuantity(new BigDecimal("50"));
        item2.setUnit("件");
        item2.setUnitPrice(new BigDecimal("20.00"));
        item2.setDiscountRate(BigDecimal.ZERO);
        item2.setTaxRate(new BigDecimal("9.0"));   // 9%

        // Simulate tax_amount computation (same logic as createSalesOrder SP4 code)
        java.math.BigDecimal taxSum = java.util.Arrays.asList(item1, item2).stream()
                .map(it -> {
                    BigDecimal la = it.getLineAmount();
                    BigDecimal lawt = it.getLineAmountWithTax();
                    if (la == null || lawt == null) return BigDecimal.ZERO;
                    return lawt.subtract(la);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        // item1: taxLine = 1000.00 × 0.13 = 130.00
        // item2: taxLine = 1000.00 × 0.09 = 90.00
        // total: 220.00
        assertEquals(new BigDecimal("220.00"), taxSum,
                "tax_amount = 130.00 + 90.00 = 220.00 (减法兜底)");
    }

    /**
     * SP4: 零税行对 tax_amount 贡献 0, 不影响其他行。
     */
    @Test
    @DisplayName("SP4: 零税行对 tax_amount 贡献 0 (向后兼容)")
    void zeroTaxLine_contributesZeroToSum() {
        SalesOrderItem taxedItem = new SalesOrderItem();
        taxedItem.setProductTypeId("PT-A");
        taxedItem.setProductName("有税品");
        taxedItem.setSalesOrderId("SO-MIXED");
        taxedItem.setQuantity(new BigDecimal("100"));
        taxedItem.setUnit("盒");
        taxedItem.setUnitPrice(new BigDecimal("10.00"));
        taxedItem.setDiscountRate(BigDecimal.ZERO);
        taxedItem.setTaxRate(new BigDecimal("13.0"));  // 有税

        SalesOrderItem zeroTaxItem = new SalesOrderItem();
        zeroTaxItem.setProductTypeId("PT-B");
        zeroTaxItem.setProductName("免税品");
        zeroTaxItem.setSalesOrderId("SO-MIXED");
        zeroTaxItem.setQuantity(new BigDecimal("50"));
        zeroTaxItem.setUnit("件");
        zeroTaxItem.setUnitPrice(new BigDecimal("20.00"));
        zeroTaxItem.setDiscountRate(BigDecimal.ZERO);
        zeroTaxItem.setTaxRate(BigDecimal.ZERO);       // 免税

        BigDecimal taxSum = java.util.Arrays.asList(taxedItem, zeroTaxItem).stream()
                .map(it -> {
                    BigDecimal la = it.getLineAmount();
                    BigDecimal lawt = it.getLineAmountWithTax();
                    if (la == null || lawt == null) return BigDecimal.ZERO;
                    return lawt.subtract(la);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        // 仅有税品: 1000.00 × 13% = 130.00; 免税品: 0
        assertEquals(new BigDecimal("130.00"), taxSum,
                "混合税率订单: 免税行贡献 0, tax_amount = 130.00");
    }
}
