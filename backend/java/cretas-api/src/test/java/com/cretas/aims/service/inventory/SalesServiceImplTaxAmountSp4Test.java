package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.FinanceCostBreakdown;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * SP4: SalesServiceImpl — tax_amount 汇总 + FinanceCostBreakdown 双值暴露。
 *
 * <p>验收标准 (spec §4 卡SP4):
 * <ol>
 *   <li>FinanceCostBreakdown 响应含 taxAmount (= SalesOrder.taxAmount) 字段</li>
 *   <li>FinanceCostBreakdown 响应含 totalAmountWithTax = totalAmount + taxAmount</li>
 *   <li>零税率/无税率订单: taxAmount=0, totalAmountWithTax=totalAmount (向后兼容)</li>
 *   <li>旧订单 taxAmount=null → 视为 0, 不 NPE</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP4: FinanceCostBreakdown 税额双值暴露")
class SalesServiceImplTaxAmountSp4Test {

    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private com.cretas.aims.repository.inventory.SalesOrderItemRepository salesOrderItemRepository;

    private SalesServiceImpl salesService;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-SP4-001";
    private static final String PRODUCT_ID = "PT-SP4";

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                null, null, null, null, null, null);
    }

    /**
     * 构建含 taxAmount 的 SalesOrder (simulate createSalesOrder 已写 taxAmount).
     */
    private SalesOrder makeOrderWithTax(BigDecimal unitPrice, BigDecimal taxRate,
                                        BigDecimal taxAmountOnHeader) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_ID);
        item.setProductName("SP4 测试品");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("盒");
        item.setUnitPrice(unitPrice);
        item.setDiscountRate(BigDecimal.ZERO);
        item.setTaxRate(taxRate);
        item.setCostUnitPrice(new BigDecimal("7.0000")); // 未税成本 (SP3)

        BigDecimal totalAmount = unitPrice != null
                ? unitPrice.multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        order.setTotalAmount(totalAmount);
        order.setTaxAmount(taxAmountOnHeader);     // SP4: 订单头税额
        order.setItems(new java.util.ArrayList<>(List.of(item)));
        return order;
    }

    // ── SP4: DTO 暴露 taxAmount + totalAmountWithTax ───────────────────────────

    /**
     * 13% 税率: taxAmount=1300.00, totalAmountWithTax=11300.00
     * totalAmount=10000.00 (100 × ¥100.00)
     */
    @Test
    @DisplayName("SP4: 13% 税率 — FinanceCostBreakdown 暴露 taxAmount + totalAmountWithTax")
    void tax13pct_exposesDualValues() {
        // taxAmount 由 createSalesOrder 汇总写到 SO 头 = 100 × (100×1.13 − 100) = 1300.00
        SalesOrder order = makeOrderWithTax(
                new BigDecimal("100.00"),
                new BigDecimal("13.0"),
                new BigDecimal("1300.00")  // SP4 写入的 taxAmount
        );
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        // SP4: taxAmount 正确暴露
        assertNotNull(result.getTaxAmount(), "taxAmount 不应为 null");
        assertEquals(new BigDecimal("1300.00"), result.getTaxAmount(),
                "taxAmount = 1300.00 (100件 × ¥100 × 13%)");

        // SP4: totalAmountWithTax = totalAmount + taxAmount
        assertNotNull(result.getTotalAmountWithTax(), "totalAmountWithTax 不应为 null");
        assertEquals(new BigDecimal("11300.00"), result.getTotalAmountWithTax(),
                "totalAmountWithTax = 10000.00 + 1300.00 = 11300.00");

        // SP3: totalAmount 未税口径不变
        assertEquals(new BigDecimal("10000.00"), result.getTotalAmount(),
                "totalAmount (未税净额) 不应改变");

        // SP3: 毛利口径一致 (未税收入 − 未税成本)
        // actualCost = 100 × 7.00 = 700.00, actualProfit = 10000.00 − 700.00 = 9300.00
        assertNotNull(result.getActualCost());
        assertEquals(new BigDecimal("700.00"), result.getActualCost(),
                "actualCost = 未税成本 = 100 × 7.00 = 700.00 (SP3 口径)");
        assertNotNull(result.getActualProfit());
        assertEquals(new BigDecimal("9300.00"), result.getActualProfit(),
                "actualProfit = 未税收入 − 未税成本 = 10000.00 − 700.00 = 9300.00 (SP3 口径自洽)");
    }

    /**
     * 9% 税率: taxAmount=900.00, totalAmountWithTax=10900.00
     */
    @Test
    @DisplayName("SP4: 9% 税率 — FinanceCostBreakdown 正确暴露")
    void tax9pct_exposesDualValues() {
        SalesOrder order = makeOrderWithTax(
                new BigDecimal("100.00"),
                new BigDecimal("9.0"),
                new BigDecimal("900.00")
        );
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertEquals(new BigDecimal("900.00"), result.getTaxAmount(),
                "taxAmount = 900.00 (100件 × ¥100 × 9%)");
        assertEquals(new BigDecimal("10900.00"), result.getTotalAmountWithTax(),
                "totalAmountWithTax = 10000.00 + 900.00 = 10900.00");
    }

    // ── SP4: 零税率向后兼容 ────────────────────────────────────────────────────

    /**
     * 零税率: taxAmount=0.00, totalAmountWithTax = totalAmount (退化).
     * 向后兼容: 凭证结构退化两行。
     */
    @Test
    @DisplayName("SP4 向后兼容: 零税率 → taxAmount=0, totalAmountWithTax=totalAmount")
    void zeroTaxRate_taxAmountZero_totalWithTaxEqualsTotalAmount() {
        SalesOrder order = makeOrderWithTax(
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO          // SP4: 零税率时 taxAmount=0
        );
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertEquals(BigDecimal.ZERO, result.getTaxAmount(),
                "零税率: taxAmount=0");
        assertEquals(new BigDecimal("10000.00"), result.getTotalAmountWithTax(),
                "零税率: totalAmountWithTax = totalAmount (无附加税)");
    }

    /**
     * 旧订单 taxAmount=null → 视为 0, totalAmountWithTax=totalAmount, 不 NPE.
     * 向后兼容: 老数据无 taxAmount 字段 (迁移前)。
     */
    @Test
    @DisplayName("SP4 向后兼容: SO.taxAmount=null → FinanceCostBreakdown.taxAmount=0, 不 NPE")
    void soTaxAmountNull_gracefullyFallsBackToZero() {
        SalesOrder order = makeOrderWithTax(
                new BigDecimal("100.00"),
                new BigDecimal("13.0"),
                null    // 旧订单未设 taxAmount
        );
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        FinanceCostBreakdown result = assertDoesNotThrow(
                () -> salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID),
                "taxAmount=null 不应导致 NPE");

        // 兜底为 0 (不是 null, 不是 NPE)
        assertNotNull(result.getTaxAmount(), "taxAmount 兜底不应为 null");
        assertEquals(BigDecimal.ZERO, result.getTaxAmount(),
                "旧订单 taxAmount=null → 兜底为 0");
        assertEquals(new BigDecimal("10000.00"), result.getTotalAmountWithTax(),
                "旧订单: totalAmountWithTax = totalAmount (0 税兜底)");
    }

    // ── SP3: 毛利口径口径一致性 (与 SP4 结合验证) ──────────────────────────────

    /**
     * SP3+SP4 联合: 有税率订单下毛利口径正确 (未税收入 − 未税成本).
     * 税额不应混入毛利计算。
     */
    @Test
    @DisplayName("SP3+SP4 联合: 有税率订单下毛利口径不含税 (未税−未税)")
    void taxRatePresent_grossProfitExcludesTax() {
        // 13% 税率, 销售未税单价=10.00, 成本未税=7.00
        SalesOrder order = makeOrderWithTax(
                new BigDecimal("10.00"),
                new BigDecimal("13.0"),
                new BigDecimal("130.00")  // tax = 100 × 10.00 × 13% = 130.00
        );
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        FinanceCostBreakdown result = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        // 毛利 = 1000.00 − 700.00 = 300.00 (均未税)
        assertEquals(new BigDecimal("1000.00"), result.getTotalAmount(),
                "totalAmount = 未税净额 1000.00");
        assertEquals(new BigDecimal("700.00"), result.getActualCost(),
                "actualCost = 未税成本 700.00 (SP3 口径)");
        assertEquals(new BigDecimal("300.00"), result.getActualProfit(),
                "actualProfit = 未税收入 − 未税成本 = 300.00 (SP3 口径自洽, 税不混入毛利)");

        // 含税总额 = 1000.00 + 130.00 = 1130.00
        assertEquals(new BigDecimal("130.00"), result.getTaxAmount(),
                "taxAmount = 130.00");
        assertEquals(new BigDecimal("1130.00"), result.getTotalAmountWithTax(),
                "totalAmountWithTax = 1130.00 (SP11 凭证应收账款金额来源)");
    }
}
