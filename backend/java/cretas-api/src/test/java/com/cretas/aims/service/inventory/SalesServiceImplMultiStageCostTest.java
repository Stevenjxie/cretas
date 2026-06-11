package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.MultiStageCostBreakdown;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
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
 * 六扇门多段生产成本逐段分配测试 (两点报工成本拆分核心)。
 *
 * <p>验证 {@link SalesServiceImpl#getOrderMultiStageCost} 回溯成品←半成品←原料链:
 * <ul>
 *   <li>多段链回溯 (按 processOrder 升序排段);</li>
 *   <li>每段料 + 人工 + 制费 三分;</li>
 *   <li>半成品 unitCost 逐段累积 (#713 移动均价);</li>
 *   <li>两点报工人工 null 诚实显示 (登下一期);</li>
 *   <li>每盒口径 (段消耗 ÷ 成品盒数);</li>
 *   <li>单段兜底 (只有一段也正常);</li>
 *   <li>未投产诚实 null。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("六扇门多段成本逐段分配 (两点报工)")
class SalesServiceImplMultiStageCostTest {

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock private SemiFinishedInventoryTransactionRepository semiFinishedInventoryTransactionRepository;
    @Mock private ProductionReportRepository productionReportRepository;

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
        ReflectionTestUtils.setField(salesService, "semiFinishedInventoryRepository", semiFinishedInventoryRepository);
        ReflectionTestUtils.setField(salesService, "semiFinishedInventoryTransactionRepository",
                semiFinishedInventoryTransactionRepository);
        ReflectionTestUtils.setField(salesService, "productionReportRepository", productionReportRepository);
    }

    private SalesOrder makeOrder() {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-001");
        item.setProductName("叮咚猪舌");
        item.setQuantity(new BigDecimal("100"));
        SalesOrder order = new SalesOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
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

    private ProductionBatch makeBatch() {
        ProductionBatch b = new ProductionBatch();
        b.setId(BATCH_PK);
        b.setFactoryId(FACTORY_ID);
        b.setProductionPlanId(PLAN_ID);
        return b;
    }

    /** 一行半成品段 (一次两点报工转化)。 */
    private SemiFinishedInventory makeStage(Long id, int order, String code,
                                            String produced, String unit,
                                            String unitCost, String accumulated) {
        return SemiFinishedInventory.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .batchId(BATCH_PK)
                .intermediateBatchNo(code)
                .processOrder(order)
                .productTypeId("PT-001")
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal(produced))
                .unit(unit)
                .unitCost(unitCost == null ? null : new BigDecimal(unitCost))
                .accumulatedCost(accumulated == null ? null : new BigDecimal(accumulated))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }

    private SemiFinishedInventoryTransaction makeInTxn(Long semiId, Long reportId, String qty, String unitCostAtTxn) {
        return SemiFinishedInventoryTransaction.builder()
                .factoryId(FACTORY_ID)
                .semiFinishedId(semiId)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .quantity(new BigDecimal(qty))
                .unitCostAtTxn(unitCostAtTxn == null ? null : new BigDecimal(unitCostAtTxn))
                .reportId(reportId)
                .build();
    }

    private ProductionReport makeReport(Long id, String labor, String material) {
        ProductionReport r = new ProductionReport();
        r.setId(id);
        r.setLaborCost(labor == null ? null : new BigDecimal(labor));
        r.setMaterialCost(material == null ? null : new BigDecimal(material));
        return r;
    }

    @SuppressWarnings("unchecked")
    private void wireChain(List<SemiFinishedInventory> stages,
                           List<SemiFinishedInventoryTransaction> txns,
                           List<ProductionReport> reports) {
        when(salesOrderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(List.of(makePlan()));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY_ID), any(Collection.class)))
                .thenReturn(List.of(makeBatch()));
        when(semiFinishedInventoryRepository.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY_ID, BATCH_PK))
                .thenReturn(stages);
        for (SemiFinishedInventory s : stages) {
            List<SemiFinishedInventoryTransaction> stageTxns = txns.stream()
                    .filter(t -> t.getSemiFinishedId().equals(s.getId()))
                    .collect(java.util.stream.Collectors.toList());
            when(semiFinishedInventoryTransactionRepository
                    .findBySemiFinishedIdOrderByCreatedAtAsc(s.getId())).thenReturn(stageTxns);
        }
        // 按请求的 reportId 过滤, 模拟真实 findAllById 行为 (避免每段都拿到全部 report)
        when(productionReportRepository.findAllById(any(Iterable.class))).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            java.util.Set<Long> idSet = new java.util.HashSet<>();
            ids.forEach(idSet::add);
            return reports.stream()
                    .filter(r -> idSet.contains(r.getId()))
                    .collect(java.util.stream.Collectors.toList());
        });
    }

    @Test
    @DisplayName("多段链回溯 — 三段 (原料→半1→半2→成品) 按序排列 + 半成品 unitCost 逐段累积")
    void multiStageChainOrderedAndCostAccumulates() {
        // 段1: 原料→半成品1, 产出 540kg, 料1200 + 人工300 = 1500, unitCost 2.7778
        SemiFinishedInventory s1 = makeStage(1L, 1, "PT-001-S1", "540", "kg", "2.7778", "1500.00");
        // 段2: 半成品1→半成品2, 产出 500kg, 累积 1500(上游) + 本段料200+人工100=1800, unitCost 3.6000
        SemiFinishedInventory s2 = makeStage(2L, 2, "PT-001-S2", "500", "kg", "3.6000", "1800.00");
        // 段3: 半成品2→成品, 产出 100盒, 累积 1800 + 本段料50+人工150=2000, unitCost 20.0000
        SemiFinishedInventory s3 = makeStage(3L, 3, "PT-001-S3", "100", "盒", "20.0000", "2000.00");

        var txns = List.of(
                makeInTxn(1L, 101L, "540", "2.7778"),
                makeInTxn(2L, 102L, "500", "3.6000"),
                makeInTxn(3L, 103L, "100", "20.0000"));
        var reports = List.of(
                makeReport(101L, "300.00", "1200.00"),
                makeReport(102L, "100.00", "200.00"),
                makeReport(103L, "150.00", "50.00"));

        // 故意乱序传入, 验证服务按 processOrder 排序
        wireChain(List.of(s3, s1, s2), txns, reports);

        MultiStageCostBreakdown r = salesService.getOrderMultiStageCost(FACTORY_ID, ORDER_ID);

        assertNotNull(r);
        assertEquals(3, r.getStageCount());
        assertEquals(3, r.getStages().size());
        // 排序: 段序 1,2,3
        assertEquals(1, r.getStages().get(0).getStageOrder());
        assertEquals(2, r.getStages().get(1).getStageOrder());
        assertEquals(3, r.getStages().get(2).getStageOrder());

        // 段1: 料1200 + 人工300 = 1500
        var st1 = r.getStages().get(0);
        assertEquals(0, new BigDecimal("1200.00").compareTo(st1.getMaterialCost()));
        assertEquals(0, new BigDecimal("300.00").compareTo(st1.getLaborCost()));
        assertEquals(0, new BigDecimal("1500.00").compareTo(st1.getStageSubtotal()));
        // 半成品 unitCost 逐段累积来自 SFI.unitCost
        assertEquals(0, new BigDecimal("2.7778").compareTo(st1.getOutputUnitCost()));

        // 段3 (成品): unitCost 20.0000
        var st3 = r.getStages().get(2);
        assertEquals(0, new BigDecimal("20.0000").compareTo(st3.getOutputUnitCost()));

        // 全链段消耗合计 = 1500 + 300 + 200 = 2000 (段小计之和: 1500 + 300 + 200)
        // 段1小计=1500, 段2小计=300(料200+人工100), 段3小计=200(料50+人工150)
        assertEquals(0, new BigDecimal("2000.00").compareTo(r.getTotalChainCost()));
    }

    @Test
    @DisplayName("两点报工人工 null — 诚实显示 + laborHint (登下一期), 不伪造 0")
    void twoPointLaborNullHonest() {
        // 两点报工: 料已知, 人工登下一期 (report.laborCost=null)
        SemiFinishedInventory s1 = makeStage(1L, 1, "PT-001-S1", "540", "kg", null, "1200.00");
        var txns = List.of(makeInTxn(1L, 101L, "540", null));
        var reports = List.of(makeReport(101L, null, "1200.00"));  // labor null
        wireChain(List.of(s1), txns, reports);

        MultiStageCostBreakdown r = salesService.getOrderMultiStageCost(FACTORY_ID, ORDER_ID);

        var st = r.getStages().get(0);
        assertNull(st.getLaborCost(), "人工应诚实 null, 不伪造");
        assertNotNull(st.getLaborHint(), "人工 null 时应有 hint");
        assertTrue(st.getLaborHint().contains("下一期") || st.getLaborHint().contains("登"),
                "hint 应说明登下一期");
        // 料仍可见
        assertEquals(0, new BigDecimal("1200.00").compareTo(st.getMaterialCost()));
        // 段小计 = 只有料 1200 (人工 null 不计)
        assertEquals(0, new BigDecimal("1200.00").compareTo(st.getStageSubtotal()));
    }

    @Test
    @DisplayName("每盒口径 — 段消耗 ÷ 成品盒数 = 每盒贡献")
    void perBoxContribution() {
        // 末段产出 100盒
        SemiFinishedInventory s1 = makeStage(1L, 1, "PT-001-S1", "100", "盒", "20.0000", "2000.00");
        var txns = List.of(makeInTxn(1L, 101L, "100", "20.0000"));
        var reports = List.of(makeReport(101L, "800.00", "1200.00"));  // 段小计 2000
        wireChain(List.of(s1), txns, reports);

        MultiStageCostBreakdown r = salesService.getOrderMultiStageCost(FACTORY_ID, ORDER_ID);

        assertEquals(0, new BigDecimal("100").compareTo(r.getFinalOutputBoxes()));
        var st = r.getStages().get(0);
        // 每盒 = 2000 / 100 = 20.00
        assertEquals(0, new BigDecimal("20.00").compareTo(st.getContributionPerBox()));
        // 全链每盒合计
        assertEquals(0, new BigDecimal("20.00").compareTo(r.getTotalCostPerBox()));
    }

    @Test
    @DisplayName("单段兜底 — 只有一段 (一次领料入+产出出) 也正常")
    void singleStageFallback() {
        SemiFinishedInventory s1 = makeStage(1L, 1, "PT-001-S1", "100", "盒", "15.0000", "1500.00");
        var txns = List.of(makeInTxn(1L, 101L, "100", "15.0000"));
        var reports = List.of(makeReport(101L, "500.00", "1000.00"));
        wireChain(List.of(s1), txns, reports);

        MultiStageCostBreakdown r = salesService.getOrderMultiStageCost(FACTORY_ID, ORDER_ID);

        assertEquals(1, r.getStageCount());
        assertEquals(0, new BigDecimal("1500.00").compareTo(r.getTotalChainCost()));
    }

    @Test
    @DisplayName("未投产 — 诚实 null (空 stages + dataSourceHint)")
    void notInProductionHonest() {
        when(salesOrderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(List.of());

        MultiStageCostBreakdown r = salesService.getOrderMultiStageCost(FACTORY_ID, ORDER_ID);

        assertNotNull(r);
        assertEquals(0, r.getStageCount());
        assertTrue(r.getStages().isEmpty());
        assertNotNull(r.getDataSourceHint());
        assertNull(r.getTotalChainCost());
    }

    @Test
    @DisplayName("无半成品 WIP 段 — 批次存在但无 SFI 行 (纯成品直产) 诚实 hint")
    void noWipStagesHonest() {
        when(salesOrderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(makeOrder()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderId(FACTORY_ID, ORDER_ID))
                .thenReturn(List.of(makePlan()));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(eq(FACTORY_ID), any(Collection.class)))
                .thenReturn(List.of(makeBatch()));
        when(semiFinishedInventoryRepository.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY_ID, BATCH_PK))
                .thenReturn(List.of());

        MultiStageCostBreakdown r = salesService.getOrderMultiStageCost(FACTORY_ID, ORDER_ID);

        assertNotNull(r);
        assertEquals(0, r.getStageCount());
        assertNotNull(r.getDataSourceHint());
    }
}
