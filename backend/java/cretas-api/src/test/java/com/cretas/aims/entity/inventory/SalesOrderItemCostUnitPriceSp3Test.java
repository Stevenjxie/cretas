package com.cretas.aims.entity.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP3: SalesOrderItem.costUnitPrice 口径统一 — 未税净价存储验证。
 *
 * <p>核心口径约束:
 * <ul>
 *   <li>costUnitPrice = 未税成本单价 (与 unitPrice 未税销售单价口径一致)</li>
 *   <li>毛利口径 = 未税收入 − 未税成本, 口径自洽</li>
 *   <li>向后兼容: 无税率数据时 costUnitPrice 视为未税, 数值不变</li>
 * </ul>
 *
 * <p>与 SP3 spec §2.3 方案A 保持一致。
 */
@DisplayName("SP3: SalesOrderItem.costUnitPrice 未税口径约束")
class SalesOrderItemCostUnitPriceSp3Test {

    private SalesOrderItem itemWith(BigDecimal sellPrice, BigDecimal costPrice, BigDecimal taxRate) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-SP3");
        item.setProductName("含税口径测试品");
        item.setSalesOrderId("SO-SP3");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("盒");
        item.setUnitPrice(sellPrice);
        item.setDiscountRate(BigDecimal.ZERO);
        item.setCostUnitPrice(costPrice);
        item.setTaxRate(taxRate);
        return item;
    }

    /**
     * SP3 核心: 毛利口径一致性。
     * 未税收入 − 未税成本 = 真实毛利 (不含税额)。
     * qty=100, sellPrice=10.00 (未税), costPrice=7.00 (未税, SP3修正后)
     * 期望: lineAmount = 1000.00 (未税), costTotal = 700.00 (未税)
     *       毛利 = 300.00, 口径自洽。
     */
    @Test
    @DisplayName("SP3: 未税收入 − 未税成本 = 口径自洽毛利")
    void grossProfitIsConsistentPreTax() {
        SalesOrderItem item = itemWith(
                new BigDecimal("10.00"),
                new BigDecimal("7.00"),
                new BigDecimal("13.0")
        );

        BigDecimal lineAmount = item.getLineAmount();
        BigDecimal costTotal = item.getCostTotal();

        // 未税收入
        assertEquals(new BigDecimal("1000.00"), lineAmount,
                "lineAmount (未税) = qty × unitPrice = 100 × 10.00 = 1000.00");

        // 未税成本 (SP3: costUnitPrice 存未税)
        assertEquals(new BigDecimal("700.00"), costTotal,
                "costTotal (未税) = qty × costUnitPrice = 100 × 7.00 = 700.00");

        // 毛利 = 未税收入 − 未税成本 (口径自洽, 不受税率影响)
        BigDecimal grossProfit = lineAmount.subtract(costTotal);
        assertEquals(new BigDecimal("300.00"), grossProfit,
                "毛利 = 未税收入 − 未税成本 = 1000.00 − 700.00 = 300.00 (口径自洽)");
    }

    /**
     * SP3 向后兼容: taxRate=null 时 costUnitPrice 视为未税 (税率0 → 含税=未税).
     * 成本不应因 taxRate=null 而变化。
     */
    @Test
    @DisplayName("SP3 向后兼容: taxRate=null 时成本口径不变 (税率0 等价)")
    void backwardCompatibility_nullTaxRate_costUnchanged() {
        SalesOrderItem item = itemWith(
                new BigDecimal("10.00"),
                new BigDecimal("7.00"),
                null   // 历史无税率数据
        );

        // taxRate=null → 含税价 = 未税价 (0税), 成本数值不变
        assertEquals(new BigDecimal("700.00"), item.getCostTotal(),
                "向后兼容: taxRate=null 时 costTotal 不变 (视为0税)");
        assertEquals(new BigDecimal("1000.00"), item.getLineAmount(),
                "向后兼容: 收入口径不受 taxRate=null 影响");
    }

    /**
     * SP3 向后兼容: taxRate=0 时成本口径与 taxRate=null 等价。
     */
    @Test
    @DisplayName("SP3 向后兼容: taxRate=0 成本不变")
    void backwardCompatibility_zeroTaxRate_costUnchanged() {
        SalesOrderItem item = itemWith(
                new BigDecimal("10.00"),
                new BigDecimal("7.00"),
                BigDecimal.ZERO
        );

        assertEquals(new BigDecimal("700.00"), item.getCostTotal(),
                "taxRate=0 时成本不变");
    }

    /**
     * SP3: costUnitPrice=null → getCostTotal()=null (诚实 null, 不返回 0)。
     * 符合 FinanceCostBreakdown B2 fix: costUnitPrice=null → anyActualMissing=true。
     */
    @Test
    @DisplayName("SP3: costUnitPrice=null → getCostTotal()=null (诚实 null)")
    void costUnitPriceNull_getCostTotalReturnsNull() {
        SalesOrderItem item = itemWith(
                new BigDecimal("10.00"),
                null,   // 成本未录入
                new BigDecimal("13.0")
        );

        assertNull(item.getCostTotal(),
                "costUnitPrice=null → getCostTotal()=null, 不返回 0 (避免误报成本=0)");
    }

    /**
     * SP3: 含税行金额与未税行金额的差值 = 税额 (用于凭证价税分离验证)。
     * qty=100, unitPrice=10.00, taxRate=13%
     * lineAmount = 1000.00 (未税)
     * lineAmountWithTax = 1130.00 (含税)
     * 税额 = 130.00
     */
    @Test
    @DisplayName("SP3: lineAmountWithTax − lineAmount = 行税额 (用减法兜底)")
    void taxAmountBySubtraction_isConsistent() {
        SalesOrderItem item = itemWith(
                new BigDecimal("10.00"),
                new BigDecimal("7.00"),
                new BigDecimal("13.0")
        );

        BigDecimal lineAmount = item.getLineAmount();           // 未税 = 1000.00
        BigDecimal lineAmountWithTax = item.getLineAmountWithTax(); // 含税 = 1130.00
        BigDecimal taxBySubtraction = lineAmountWithTax.subtract(lineAmount); // 减法兜底

        assertEquals(new BigDecimal("1000.00"), lineAmount);
        assertEquals(new BigDecimal("1130.00"), lineAmountWithTax);
        assertEquals(new BigDecimal("130.00"), taxBySubtraction,
                "税额 = 含税 − 未税 = 130.00 (减法兜底防舍入裂缝)");
    }
}
