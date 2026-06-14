package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.FinanceCostBreakdown;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import com.cretas.aims.service.bom.impl.CostVarianceServiceImpl;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 六扇门 D1: getOrderCostBreakdown 同口径标准成本 — 修复假阳性超支报警.
 *
 * <p>核心断言:
 * <ul>
 *   <li>标准含人工 (laborIncluded=true) → 标准成本含人工, 与含人工实际同口径 → 超支正常计算</li>
 *   <li>标准缺人工 (laborIncluded=false, totalUnitCost=null) → 订单/行级超支跳过 (不误报),
 *       hint 提示缺研发预估人工</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("D1: 同口径标准成本 — 假阳性超支报警修复")
class SalesServiceImplStandardCostCaliberD1Test {

    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private com.cretas.aims.repository.inventory.SalesOrderItemRepository salesOrderItemRepository;
    @Mock
    private StandardCostService standardCostService;

    private SalesServiceImpl salesService;
    private final CostVarianceService costVarianceService = new CostVarianceServiceImpl(null) {
        // 用真实方差公式但绕过 DB 阈值查询 → 固定 10%
        @Override
        public BigDecimal resolveThreshold(String factoryId, String productTypeId) {
            return new BigDecimal("10.00");
        }
    };

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-猪舌-001";
    private static final String PRODUCT_ID = "PT-猪舌";

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository, salesOrderItemRepository,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(salesService, "standardCostService", standardCostService);
        ReflectionTestUtils.setField(salesService, "costVarianceService", costVarianceService);
    }

    private SalesOrder makeOrder(BigDecimal sellPrice, BigDecimal costPrice) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_ID);
        item.setProductName("卤猪舌 200g");
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(sellPrice);
        item.setCostUnitPrice(costPrice);

        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setStatus(SalesOrderStatus.PENDING_FINANCE_REVIEW);
        order.setTotalAmount(sellPrice != null ? sellPrice.multiply(BigDecimal.TEN) : null);
        order.setItems(new java.util.ArrayList<>(List.of(item)));
        return order;
    }

    private StandardCostService.StandardUnitCost std(BigDecimal total, boolean laborIncluded) {
        return StandardCostService.StandardUnitCost.builder()
                .materialUnitCost(new BigDecimal("8.00"))
                .laborUnitCost(laborIncluded ? new BigDecimal("2.00") : null)
                .totalUnitCost(total)
                .laborIncluded(laborIncluded)
                .caliberHint(laborIncluded ? "标准成本含人工" : "口径不全: 缺研发预估人工")
                .build();
    }

    // ── D1-A: 标准含人工 → 同口径 → 超支正常计算 (实际 11 vs 标准 10 = +10%, 不超阈值) ──
    @Test
    @DisplayName("标准含人工 → 同口径超支正常计算")
    void standardWithLabor_sameCaliber_varianceComputed() {
        // 实际成本/盒 = 10.50; 标准 (含人工) = 10.00 → +5% < 10% 阈值 → 不报警
        SalesOrder order = makeOrder(new BigDecimal("20.00"), new BigDecimal("10.50"));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(standardCostService.resolveStandardUnitCost(FACTORY_ID, PRODUCT_ID))
                .thenReturn(std(new BigDecimal("10.0000"), true));

        FinanceCostBreakdown r = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        assertNotNull(r.getBomStandardCost(), "标准成本含人工应可用");
        assertEquals(0, r.getBomStandardCost().compareTo(new BigDecimal("100.00"))); // 10 盒 × 10
        assertNotNull(r.getActualCost());
        // 超支率 = (105 - 100) / 100 = +5% → belowThreshold true, 无 alarm
        assertNotNull(r.getVariancePct());
        assertEquals(0, r.getVariancePct().compareTo(new BigDecimal("5.00")));
        assertEquals(Boolean.TRUE, r.getBelowThreshold());
        assertNull(r.getAlarmMessage(), "未超阈值不报警");
    }

    // ── D1-B: 标准缺人工 → 口径不全 → 超支跳过不误报 + hint 提示缺人工 ──
    @Test
    @DisplayName("标准缺人工 → 假阳性守卫 → 超支跳过 + hint 提示")
    void standardMissingLabor_skipsVariance_noFalsePositive() {
        // 实际 13.00 (含人工) vs 若用纯料 8.00 → +62.5% 假超支. 守卫应跳过.
        SalesOrder order = makeOrder(new BigDecimal("20.00"), new BigDecimal("13.00"));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(standardCostService.resolveStandardUnitCost(FACTORY_ID, PRODUCT_ID))
                .thenReturn(std(null, false)); // totalUnitCost null, laborIncluded false

        FinanceCostBreakdown r = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        // 标准 totalUnitCost null → bomStandardCost null (诚实留空)
        assertNull(r.getBomStandardCost(), "口径不全 → 标准成本 null");
        assertNotNull(r.getActualCost(), "实际成本仍可用");
        // 行级 + 订单级超支全部跳过 (不误报)
        assertNull(r.getLines().get(0).getVariancePct(), "行级超支跳过 (假阳性守卫)");
        assertNull(r.getVariancePct(), "订单级超支跳过 (假阳性守卫)");
        assertNull(r.getAlarmMessage(), "不报假阳性超支");
        // totalUnitCost=null → bomStandardCost null → 走 B2 "标准成本不可用" hint (无可比标准)
        assertNotNull(r.getDataSourceHint(), "hint 应说明标准成本不可用");
    }

    // ── D1-C: 标准缺人工但有料标准 → 仍跳过订单超支 (有 actual 时拦截) ──
    @Test
    @DisplayName("标准只有料 (laborIncluded=false 但 total 有值) → 仍守卫订单超支")
    void standardLaborFlagFalse_orderVarianceGuarded() {
        // 极端: total 有值但 laborIncluded=false (退化场景, 守卫以 flag 为准)
        SalesOrder order = makeOrder(new BigDecimal("20.00"), new BigDecimal("13.00"));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        lenient().when(standardCostService.resolveStandardUnitCost(FACTORY_ID, PRODUCT_ID))
                .thenReturn(StandardCostService.StandardUnitCost.builder()
                        .materialUnitCost(new BigDecimal("8.00"))
                        .totalUnitCost(new BigDecimal("8.0000"))
                        .laborIncluded(false)
                        .caliberHint("仅料")
                        .build());

        FinanceCostBreakdown r = salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID);

        // bomUnit=8.00 进 bomStandardCost, 但 laborIncluded=false → 行级 + 订单级超支跳过
        assertNotNull(r.getBomStandardCost(), "料标准有值 → bomStandardCost 非 null");
        assertNull(r.getLines().get(0).getVariancePct(), "laborIncluded=false → 行级跳过");
        assertNull(r.getVariancePct(), "laborIncluded=false → 订单级跳过");
        assertNull(r.getAlarmMessage());
        // 此分支 (有标准但缺人工口径) → D1 专属 hint 提示缺研发预估人工
        assertNotNull(r.getDataSourceHint());
        assertTrue(r.getDataSourceHint().contains("人工"),
                "D1 hint 应提示标准成本未含研发预估人工: " + r.getDataSourceHint());
    }
}
