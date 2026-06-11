package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.FinanceCostBreakdown;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SP12: 财务实际成本拆分 (材料逐料 + 人工 + 制费).
 *
 * <p>客户原话 (六扇门 [12:36]): "财务根据前面领料的批次去核算最终的实际成本"。
 * 验证 getOrderCostBreakdown 返回的 {@link FinanceCostBreakdown#getActualCostSplit()}:
 * <ul>
 *   <li>材料: 从 MaterialConsumption 领料记录按物料聚合 (用量/移动均价/金额);</li>
 *   <li>人工: 从关联批次 laborCost rollup;</li>
 *   <li>制费: 从关联批次 equipmentCost + otherCost;</li>
 *   <li>诚实 null: 无关联计划 / 未领料 / 未报工时该组分 null, 不伪造数字。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SP12: 财务实际成本拆分 (材料/人工/制费)")
class SalesServiceImplActualCostSplitSp12Test {

    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private ProductionBatchRepository productionBatchRepository;
    @Mock
    private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    private SalesServiceImpl salesService;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-001";
    private static final String PLAN_ID = "PP-001";
    private static final Long BATCH_PK = 1924L;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(salesService, "productionPlanRepository", productionPlanRepository);
        ReflectionTestUtils.setField(salesService, "productionBatchRepository", productionBatchRepository);
        ReflectionTestUtils.setField(salesService, "materialConsumptionRepository", materialConsumptionRepository);
        ReflectionTestUtils.setField(salesService, "rawMaterialTypeRepository", rawMaterialTypeRepository);
    }

    private SalesOrder makeOrder() {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-001");
        item.setProductName("叮咚猪舌");
        item.setQuantity(new BigDecimal("100"));
        item.setUnitPrice(new BigDecimal("9.00"));
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setTotalAmount(new BigDecimal("900.00"));
        order.setItems(new java.util.ArrayList<>(List.of(item)));
        return order;
    }

    private ProductionPlan makePlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setSourceOrderId(ORDER_ID);
        return plan;
    }

    private ProductionBatch makeBatch(BigDecimal labor, BigDecimal equip, BigDecimal other) {
        ProductionBatch b = new ProductionBatch();
        b.setId(BATCH_PK);
        b.setFactoryId(FACTORY_ID);
        b.setProductionPlanId(PLAN_ID);
        b.setLaborCost(labor);
        b.setEquipmentCost(equip);
        b.setOtherCost(other);
        return b;
    }

    private MaterialConsumption makeConsumption(String typeId, String qty, String unitPrice, String totalCost) {
        MaterialConsumption c = new MaterialConsumption();
        c.setMaterialTypeId(typeId);
        c.setQuantity(new BigDecimal(qty));
        c.setUnitPrice(new BigDecimal(unitPrice));
        c.setTotalCost(new BigDecimal(totalCost));
        return c;
    }

    @Test
    @DisplayName("完整数据: 材料逐料聚合 + 人工 + 制费三分, 合计正确")
    void fullSplit() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(List.of(makePlan()));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY_ID), any(Collection.class)))
                .thenReturn(List.of(makeBatch(new BigDecimal("300.00"), new BigDecimal("50.00"), new BigDecimal("20.00"))));
        // 两种原料 + 同一原料两笔领料 (验证按 type 聚合)
        when(materialConsumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_PK, FACTORY_ID))
                .thenReturn(List.of(
                        makeConsumption("MT-PIG", "50", "10.00", "500.00"),
                        makeConsumption("MT-PIG", "30", "10.00", "300.00"),
                        makeConsumption("MT-SALT", "5", "4.00", "20.00")
                ));
        RawMaterialType pig = new RawMaterialType();
        pig.setName("猪舌"); pig.setCategory("原料"); pig.setUnit("kg");
        RawMaterialType salt = new RawMaterialType();
        salt.setName("食盐"); salt.setCategory("辅料"); salt.setUnit("kg");
        when(rawMaterialTypeRepository.findById("MT-PIG")).thenReturn(Optional.of(pig));
        when(rawMaterialTypeRepository.findById("MT-SALT")).thenReturn(Optional.of(salt));

        FinanceCostBreakdown.ActualCostSplit split =
                salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID).getActualCostSplit();

        assertNotNull(split);
        assertEquals(1, split.getBatchCount());
        // 材料 = 500 + 300 + 20 = 820
        assertEquals(0, new BigDecimal("820.00").compareTo(split.getMaterialCost()));
        // 人工 = 300
        assertEquals(0, new BigDecimal("300.00").compareTo(split.getLaborCost()));
        // 制费 = 50 + 20 = 70
        assertEquals(0, new BigDecimal("70.00").compareTo(split.getOverheadCost()));
        // 合计 = 820 + 300 + 70 = 1190
        assertEquals(0, new BigDecimal("1190.00").compareTo(split.getTotalActualCost()));

        // 逐料: 2 行 (猪舌聚合 80kg / 800元, 食盐 5kg / 20元)
        assertEquals(2, split.getMaterials().size());
        FinanceCostBreakdown.MaterialCostLine pigLine = split.getMaterials().stream()
                .filter(m -> "MT-PIG".equals(m.getMaterialTypeId())).findFirst().orElseThrow();
        assertEquals("猪舌", pigLine.getMaterialName());
        assertEquals("原料", pigLine.getCategory());
        assertEquals(0, new BigDecimal("80").compareTo(pigLine.getActualQuantity()));
        assertEquals(0, new BigDecimal("800.00").compareTo(pigLine.getAmount()));
        // 移动均价 = 800 / 80 = 10
        assertEquals(0, new BigDecimal("10").compareTo(pigLine.getUnitPrice()));
        assertNull(split.getDataSourceHint());
    }

    @Test
    @DisplayName("订单未投产: split 非 null, 组分全 null, hint 说明未投产")
    void noPlan() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(java.util.Collections.emptyList());

        FinanceCostBreakdown.ActualCostSplit split =
                salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID).getActualCostSplit();

        assertNotNull(split);
        assertEquals(0, split.getBatchCount());
        assertNull(split.getMaterialCost());
        assertNull(split.getLaborCost());
        assertNull(split.getOverheadCost());
        assertNull(split.getTotalActualCost());
        assertTrue(split.getMaterials().isEmpty());
        assertNotNull(split.getDataSourceHint());
        assertTrue(split.getDataSourceHint().contains("未投产"));
    }

    @Test
    @DisplayName("批次未领料未报工: 各组分诚实 null, hint 列出缺失项")
    void batchNoData() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(List.of(makePlan()));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY_ID), any(Collection.class)))
                .thenReturn(List.of(makeBatch(null, null, null)));
        when(materialConsumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_PK, FACTORY_ID))
                .thenReturn(java.util.Collections.emptyList());

        FinanceCostBreakdown.ActualCostSplit split =
                salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID).getActualCostSplit();

        assertNotNull(split);
        assertEquals(1, split.getBatchCount());
        assertNull(split.getMaterialCost());
        assertNull(split.getLaborCost());
        assertNull(split.getOverheadCost());
        assertNull(split.getTotalActualCost());
        assertNotNull(split.getDataSourceHint());
        assertTrue(split.getDataSourceHint().contains("领料"));
        assertTrue(split.getDataSourceHint().contains("人工"));
    }

    @Test
    @DisplayName("只有材料无人工: materialCost 非 null, laborCost null, 合计=材料")
    void materialOnly() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(List.of(makePlan()));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY_ID), any(Collection.class)))
                .thenReturn(List.of(makeBatch(null, null, null)));
        when(materialConsumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_PK, FACTORY_ID))
                .thenReturn(List.of(makeConsumption("MT-PIG", "10", "10.00", "100.00")));
        RawMaterialType pig = new RawMaterialType();
        pig.setName("猪舌"); pig.setCategory("原料"); pig.setUnit("kg");
        when(rawMaterialTypeRepository.findById("MT-PIG")).thenReturn(Optional.of(pig));

        FinanceCostBreakdown.ActualCostSplit split =
                salesService.getOrderCostBreakdown(FACTORY_ID, ORDER_ID).getActualCostSplit();

        assertNotNull(split);
        assertEquals(0, new BigDecimal("100.00").compareTo(split.getMaterialCost()));
        assertNull(split.getLaborCost());
        assertNull(split.getOverheadCost());
        assertEquals(0, new BigDecimal("100.00").compareTo(split.getTotalActualCost()));
    }

    @Test
    @DisplayName("repos 未注册 (单测/模块未部署): actualCostSplit 整个为 null")
    void reposUnavailable() {
        SalesServiceImpl bare = new SalesServiceImpl(
                salesOrderRepository, null, null, null, null, null, null, null);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(makeOrder()));

        FinanceCostBreakdown.ActualCostSplit split =
                bare.getOrderCostBreakdown(FACTORY_ID, ORDER_ID).getActualCostSplit();

        assertNull(split);
    }
}
