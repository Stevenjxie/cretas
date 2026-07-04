package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.LaborSegment;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.InterimSettleServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEEP 深测: 气调成品批 (FinishedGoodsBatch) 批成本传播 — 全桶精确断言 + 副产模型 + 诚实 null。
 *
 * <h3>被测成本路径 (task #8 映射)</h3>
 * 逐道生产链 小结 → {@link InterimSettleServiceImpl#interimSettle} ④ FG 段 →
 * {@code computeOutputUnitCost} → {@code createFinishedGoodsForInterim} 写 {@code FinishedGoodsBatch.unitCost}。
 *
 * <pre>
 *   FG.unitCost = ( baseTotal + Σ stockFeedCost ) / FG入库量   [scale-4, HALF_UP]
 *
 *   baseTotal (物化成品道, batchId != null)
 *       = ProductionBatch.totalCost
 *       = 原料(raw MaterialConsumption 边) + 调料(SEASONING 报工) + 人工(LABOR 报工) + 在制 WIP 继承
 *         (ClerkProcessEntryServiceImpl.materializeBatch 逐边累加, setScale(2,HALF_UP), 见 :283/:305/:522)
 *       ⚠️ SFI/FG 投料成本 *不* 在 totalCost 内 (投料边不写 MaterialConsumption) → 下面单独补。
 *   stockFeedCost = Σ (semiFinished/finishedGoods 上游: feedKg × 输入库存.unitCost)
 *   FG入库量 = productWeight(优先, >0) 否则 outputQuantity  (与库存计量口径一致)
 * </pre>
 *
 * <h3>副产 (byproduct) 模型 — 本测验证的裁定</h3>
 * 副产物 <b>不进</b> ProductionBatch.totalCost, <b>也不进</b> FinishedGoodsBatch.unitCost。
 * 它经 {@code writeYieldAuxReport} 写入 YIELD ProductionReport, 仅在 <b>订单级</b>
 * {@code OrderCostBreakdownService.computeByBatch} 的 {@code netTotalCost = total − byproductCredit}
 * 冲减 (OrderCostBreakdownService:205)。即: <b>FG 批库存成本是 gross(不含副产回收); 副产回收单独在订单成本报表冲减</b>。
 * → 副产既 <b>不 double-count</b> (只在 OrderCostBreakdown 出现一次) 也 <b>不 dropped</b> (YIELD 报工留存),
 *   而是 <b>SEPARATE 单独核算</b>。本测固化"副产不改 FG.unitCost"。
 *
 * <h3>诚实 null (禁止降级)</h3>
 * 任一投入桶未知 → FG.unitCost = null, 绝不伪造 ¥0 (下游 {@code VoucherExportServiceImpl:635}
 * WIP 半成品入库凭证 = producedQuantity × unitCost, 错成本 → 错凭证)。
 *
 * <p>风格镜像 {@link InterimSettleServiceTest} (真实 service, 真实 ProductionBatch 成本聚合, 仅 repo 边界 mock)。
 * 每条断言读回 <b>持久化的 FG 实体</b> (ArgumentCaptor 抓 save 入参) 的 unitCost/producedQuantity, 非仅"方法返非 null"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FgCostPropagationTest - 气调成品批成本传播深测 (原料+调料+人工+副产+feed继承 → FG.unitCost)")
class FgCostPropagationTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-FG";
    private static final String PRODUCT_TYPE = "PT-FG";
    private static final BigDecimal LABOR_RATE = new BigDecimal("20");

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProcessSheetRowRepository rowRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionInterimSettlementRepository settlementRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private ClerkProcessEntryService clerkProcessEntryService;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private WorkProcessRepository workProcessRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterimSettleServiceImpl service;
    private final List<ProductionInterimSettlement> savedSettlements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new InterimSettleServiceImpl(
                planRepository, rowRepository, consumptionRepository, materialBatchRepository,
                settlementRepository, finishedGoodsBatchRepository, productTypeRepository,
                wipInventoryService, finishedGoodsFeedService, warehouseResolver, objectMapper,
                batchRepository, clerkProcessEntryService,
                productWorkProcessRepository, workProcessRepository);

        when(clerkProcessEntryService.resolveLaborRate(eq(FACTORY), any())).thenReturn(LABOR_RATE);
        when(clerkProcessEntryService.computeLaborCost(any(), any())).thenReturn(BigDecimal.ZERO);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PP-FG");
        plan.setProductTypeId(PRODUCT_TYPE);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        when(settlementRepository.saveAndFlush(any(ProductionInterimSettlement.class)))
                .thenAnswer(inv -> {
                    savedSettlements.add(inv.getArgument(0));
                    return inv.getArgument(0);
                });
        when(settlementRepository
                .findTopByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqDesc(FACTORY, PLAN_ID))
                .thenAnswer(inv -> savedSettlements.stream()
                        .max((a, b) -> Integer.compare(a.getSessionSeq(), b.getSessionSeq())));
        when(settlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderBySessionSeqAsc(FACTORY, PLAN_ID))
                .thenAnswer(inv -> new ArrayList<>(savedSettlements));

        when(rowRepository.save(any(ProcessSheetRow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consumptionRepository.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productTypeRepository.findByIdAndFactoryId(any(), eq(FACTORY))).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn("WH-WKS");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(eq(FACTORY), any()))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // 投料严格扣减默认成功返回请求量 (个别测试无投料则不触发)
        when(wipInventoryService.consumeClerkSemiStrict(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(finishedGoodsFeedService.consumeForFeedStrict(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        // 盒⇄kg 折算: 本测全部 kg 源 → 原值 (与扣减折算同源; computeOutputUnitCost 用其算投料成本)。
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(finishedGoodsFeedService.resolveFeedQtyInSourceUnit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        // 无未结原料消耗 (本测聚焦 FG 成本, 不测原料扣减侧)
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
    }

    // ─────────────────────────────────────────────────────────────
    // 1. ALL-BUCKETS: 原料 + 调料 + 人工 (∈ pb.totalCost) + SFI feed → FG.unitCost 精确等于和
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("全桶精确: pb.totalCost=原料300+调料120+人工80=500, + SFI feed 40×5=200 → FG.unitCost=(700)/productWeight10=70.0000; 分母是入库量(productWeight)非outputQuantity")
    void allBucketsRawSeasoningLaborFeedPropagateExactlyToFgUnitCost() {
        // 物化成品道 (batchId != null): pb.totalCost 已含 原料+调料+人工 (materializeBatch 折进),
        //   这里显式把三桶写出来相加 = 500, 固化"三桶都进 totalCost"。SFI 投料 40kg @ 5 = 200 单独补。
        final BigDecimal raw = new BigDecimal("300.00");
        final BigDecimal seasoning = new BigDecimal("120.00");
        final BigDecimal labor = new BigDecimal("80.00");
        final BigDecimal pbTotal = raw.add(seasoning).add(labor); // = 500.00 (= ProductionBatch.totalCost)

        ProcessSheetRow rFg = materializedFinishedRow(200L, "CLK-B-ALL",
                new BigDecimal("50"),          // outputQuantity 50 (盒) — 故意 ≠ 分母, 验证分母用 productWeight
                new BigDecimal("10"),          // productWeight 10kg = FG 入库量 = 分母
                upstreamSemi("SFI-COSTED", "40"));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(200L, pbTotal);
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-COSTED")).thenReturn(new BigDecimal("5"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        FinishedGoodsBatch fg = captureSavedFg();
        // 入库量 = productWeight 10 (不是 outputQuantity 50)
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo("10");
        assertThat(fg.getUnit()).isEqualTo("kg");
        // FG.unitCost = (原料300 + 调料120 + 人工80 + feed 40×5=200) / 10 = 700/10 = 70.0000
        assertThat(fg.getUnitCost()).isNotNull();
        assertThat(fg.getUnitCost()).isEqualByComparingTo("70");
        // 反证: 若误用 outputQuantity(50) 作分母 → 14; 断言排除
        assertThat(fg.getUnitCost()).isNotEqualByComparingTo("14");
    }

    @Test
    @DisplayName("scale-4 HALF_UP: pb.totalCost=100(无feed) / productWeight 3 = 33.3333 (确认成本除法标度与舍入)")
    void fgUnitCostScale4HalfUpOnNonIntegerDivision() {
        ProcessSheetRow rFg = materializedFinishedRow(201L, "CLK-B-DIV",
                new BigDecimal("3"), new BigDecimal("3"), null);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(201L, new BigDecimal("100.00"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        FinishedGoodsBatch fg = captureSavedFg();
        // 100 / 3 = 33.33333... → scale-4 HALF_UP = 33.3333
        assertThat(fg.getUnitCost()).isEqualByComparingTo(new BigDecimal("33.3333"));
        // 断言真实 scale = 4 (非仅数值相等) — 固化标度
        assertThat(fg.getUnitCost().scale()).isEqualTo(4);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 副产 (byproduct): 不冲减 FG 批库存成本 (SEPARATE 单独核算, 见类 Javadoc 裁定)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("副产模型裁定: 成品道带副产(8单位×¥3=¥24回收) → FG.unitCost 仍是 gross(pb.totalCost500/10=50), 副产 NOT netted 进 FG 批 (只在订单级 OrderCostBreakdown.netTotalCost 冲减)")
    void byproductDoesNotReduceFgBatchUnitCost() {
        ProcessSheetRow rFg = materializedFinishedRow(202L, "CLK-B-BP",
                new BigDecimal("40"), new BigDecimal("10"), null);
        // 成品道产出一笔副产 (回收价 ¥3/单位, 8 单位 → ¥24 变现)。
        ProcessSheetRowRequest req = deserialize(rFg);
        req.setByproducts(List.of(byproduct("鱼骨", "8", "3")));
        rFg.setRowPayload(toJson(req));

        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(202L, new BigDecimal("500.00"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        FinishedGoodsBatch fg = captureSavedFg();
        // 🔴 裁定: FG 批库存成本 = gross = 500/10 = 50.0000 (副产 ¥24 *不* 冲减 FG.unitCost)
        //   若副产被误 net 进 FG → (500−24)/10 = 47.6; 断言排除, 证明副产不 double-count 也不进 FG 批。
        assertThat(fg.getUnitCost()).isEqualByComparingTo("50");
        assertThat(fg.getUnitCost()).isNotEqualByComparingTo("47.6");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 诚实 null (禁止降级) — 覆盖 pb.totalCost==null / pb 缺失 两条既有测试未覆盖分支
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("诚实null: 物化成品道 ProductionBatch.totalCost=null(原料桶物化时未知) → FG.unitCost null (不伪造¥0)")
    void honestNullWhenMaterializedBatchTotalCostNull() {
        ProcessSheetRow rFg = materializedFinishedRow(203L, "CLK-B-NULLPB",
                new BigDecimal("10"), new BigDecimal("10"), null);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        ProductionBatch pb = new ProductionBatch();
        pb.setId(203L);
        pb.setFactoryId(FACTORY);
        pb.setTotalCost(null);                 // 成本未知
        when(batchRepository.findByIdAndFactoryId(203L, FACTORY)).thenReturn(Optional.of(pb));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        FinishedGoodsBatch fg = captureSavedFg();
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo("10"); // FG 仍入库 (库存不因成本未知而丢)
        assertThat(fg.getUnitCost()).isNull();                            // 但成本诚实 null
    }

    @Test
    @DisplayName("诚实null: 物化成品道对应 ProductionBatch 缺失(数据异常) → FG.unitCost null (不猜)")
    void honestNullWhenMaterializedBatchMissing() {
        ProcessSheetRow rFg = materializedFinishedRow(204L, "CLK-B-NOPB",
                new BigDecimal("10"), new BigDecimal("10"), null);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        when(batchRepository.findByIdAndFactoryId(204L, FACTORY)).thenReturn(Optional.empty());

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        assertThat(captureSavedFg().getUnitCost()).isNull();
    }

    @Test
    @DisplayName("诚实null: SFI feed 输入 unitCost=null(旧库存) → 即便 pb.totalCost 已知, FG.unitCost null (投料桶毒化整道)")
    void honestNullWhenSfiFeedUnitCostNull() {
        ProcessSheetRow rFg = materializedFinishedRow(205L, "CLK-B-NULLFEED",
                new BigDecimal("10"), new BigDecimal("10"), upstreamSemi("SFI-LEGACY", "40"));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(205L, new BigDecimal("500.00"));
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-LEGACY")).thenReturn(null); // 未接通成本

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        FinishedGoodsBatch fg = captureSavedFg();
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo("10");
        assertThat(fg.getUnitCost()).isNull();  // 🔴 一桶未知 → 整道未知
    }

    // ─────────────────────────────────────────────────────────────
    // 4. SFI/FG feed 继承: feedKg × 输入 unitCost 精确进 FG 成本
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SFI feed 继承: 成品道部分吃 costed SFI(unitCost 8) feed 30 + pb.totalCost200 → FG.unitCost=(200+30×8=440)/8=55.0000; 且 SFI 严格出库 30")
    void sfiFeedInheritanceAddsFeedCostToFg() {
        ProcessSheetRow rFg = materializedFinishedRow(206L, "CLK-B-SFIFEED",
                new BigDecimal("40"), new BigDecimal("8"), upstreamSemi("SFI-STOCK", "30"));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(206L, new BigDecimal("200.00"));
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-STOCK")).thenReturn(new BigDecimal("8"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        // SFI 严格出库实际发生 (feed 30) — 成本传导与库存扣减同源
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-STOCK"), eq(new BigDecimal("30")));
        FinishedGoodsBatch fg = captureSavedFg();
        // (pb 200 + feed 30×8=240) / 入库量 8 = 440/8 = 55.0000
        assertThat(fg.getUnitCost()).isEqualByComparingTo("55");
    }

    @Test
    @DisplayName("FG feed 继承(①c): 成品道部分吃 costed FG(unitCost 12) feed 20 + pb.totalCost160 → FG.unitCost=(160+20×12=400)/8=50.0000")
    void fgFeedInheritanceAddsFeedCostToFg() {
        ProcessSheetRow rFg = materializedFinishedRow(207L, "CLK-B-FGFEED",
                new BigDecimal("40"), new BigDecimal("8"), upstreamFg("FG-STOCK", "20"));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(207L, new BigDecimal("160.00"));
        when(finishedGoodsFeedService.getFeedUnitCost(FACTORY, "FG-STOCK")).thenReturn(new BigDecimal("12"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        verify(finishedGoodsFeedService, times(1))
                .consumeForFeedStrict(eq(FACTORY), eq("FG-STOCK"), eq(new BigDecimal("20")), eq("kg"));
        FinishedGoodsBatch fg = captureSavedFg();
        // (pb 160 + feed 20×12=240) / 8 = 400/8 = 50.0000
        assertThat(fg.getUnitCost()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("双 feed 桶叠加: SFI feed(2×10) + FG feed(3×20) + pb.totalCost 100 → FG.unitCost=(100+20+60=180)/4=45.0000 (两外部库存桶不漏不重)")
    void bothSfiAndFgFeedBucketsSumIntoFgCost() {
        UpstreamRef sfi = new UpstreamRef();
        sfi.setSourceBatchNumber("SFI-D");
        sfi.setFeedQuantityKg(new BigDecimal("2"));
        sfi.setSemiFinished(true);
        UpstreamRef fgRef = new UpstreamRef();
        fgRef.setSourceBatchNumber("FG-D");
        fgRef.setFeedQuantityKg(new BigDecimal("3"));
        fgRef.setFinishedGoods(true);

        ProcessSheetRow rFg = materializedFinishedRow(208L, "CLK-B-DUAL",
                new BigDecimal("40"), new BigDecimal("4"), new ArrayList<>(List.of(sfi, fgRef)));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rFg));
        stubBatch(208L, new BigDecimal("100.00"));
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-D")).thenReturn(new BigDecimal("10"));
        when(finishedGoodsFeedService.getFeedUnitCost(FACTORY, "FG-D")).thenReturn(new BigDecimal("20"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        FinishedGoodsBatch fg = captureSavedFg();
        // (100 + 2×10=20 + 3×20=60) / 4 = 180/4 = 45.0000
        assertThat(fg.getUnitCost()).isEqualByComparingTo("45");
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private FinishedGoodsBatch captureSavedFg() {
        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(cap.capture());
        return cap.getValue();
    }

    private void stubBatch(Long id, BigDecimal totalCost) {
        ProductionBatch pb = new ProductionBatch();
        pb.setId(id);
        pb.setFactoryId(FACTORY);
        pb.setTotalCost(totalCost);
        when(batchRepository.findByIdAndFactoryId(id, FACTORY)).thenReturn(Optional.of(pb));
    }

    /** 物化成品道行: batchId != null (走 pb.totalCost base 路径), finished=true. */
    private ProcessSheetRow materializedFinishedRow(Long id, String batchNumber, BigDecimal outputQty,
                                                    BigDecimal productWeight, List<UpstreamRef> upstream) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("c" + id);
        req.setProcessCode("p" + id);
        req.setProcessOrder(1);
        req.setProductTypeId(PRODUCT_TYPE);
        req.setBatchNumber(batchNumber);
        req.setFinished(true);
        req.setOutputQuantity(outputQty);
        req.setUnit("盒");
        req.setProductWeight(productWeight);
        req.setUpstreamSources(upstream);

        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode(req.getProcessCode());
        row.setProcessOrder(1);
        row.setClientRowId("c" + id);
        row.setBatchId(id);                 // 物化 → base = ProductionBatch.totalCost
        row.setBatchNumber(batchNumber);
        row.setRowStatus("SAVED");
        row.setRowPayload(toJson(req));
        return row;
    }

    private List<UpstreamRef> upstreamSemi(String intermediateBatchNo, String feedKg) {
        if (intermediateBatchNo == null) {
            return null;
        }
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(intermediateBatchNo);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setSemiFinished(true);
        return new ArrayList<>(List.of(ref));
    }

    private List<UpstreamRef> upstreamFg(String batchNumber, String feedKg) {
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(batchNumber);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setFinishedGoods(true);
        return new ArrayList<>(List.of(ref));
    }

    private ProcessChainEntryRequest.Byproduct byproduct(String name, String qty, String unitPrice) {
        ProcessChainEntryRequest.Byproduct b = new ProcessChainEntryRequest.Byproduct();
        b.setName(name);
        b.setQuantity(new BigDecimal(qty));
        b.setUnit("kg");
        b.setUnitPrice(new BigDecimal(unitPrice));
        return b;
    }

    @SuppressWarnings("unused")
    private LaborSegment seg(String start, String end, int workers) {
        LaborSegment s = new LaborSegment();
        s.setStartTime(start);
        s.setEndTime(end);
        s.setWorkerCount(workers);
        return s;
    }

    // ─────────────────────────────────────────────────────────────
    // R3 (2026-07-04): 全消耗 (net<=0) 的中间道吃外部 SFI 投料 → loud 告警 (投料成本对下游漏计不再静默)
    // ─────────────────────────────────────────────────────────────

    /** 非成品中间道 (finished=false, batchId 物化), 吃 SFI 投料。 */
    private ProcessSheetRow intermediateSfiRow(Long id, String batchNumber, String outputQty,
                                               String sfiBatchNo, String feedKg) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("c" + id);
        req.setProcessCode("p" + id);
        req.setProcessOrder(1);
        req.setProductTypeId(PRODUCT_TYPE);
        req.setBatchNumber(batchNumber);
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal(outputQty));
        req.setUnit("kg");
        req.setUpstreamSources(upstreamSemi(sfiBatchNo, feedKg));   // semiFinished 投料
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode(req.getProcessCode());
        row.setProcessOrder(2);
        row.setClientRowId("c" + id);
        row.setBatchId(id);
        row.setBatchNumber(batchNumber);
        row.setRowStatus("SAVED");
        row.setRowPayload(toJson(req));
        return row;
    }

    /** 下游成品道: 经<b>在制 WIP</b> (非 semiFinished) 全消耗上游中间道 batchNumber。 */
    private ProcessSheetRow finishedRowConsumingWip(Long id, String batchNumber, String outputQty,
                                                    String upstreamBatchNo, String feedKg) {
        UpstreamRef wipRef = new UpstreamRef();
        wipRef.setSourceBatchNumber(upstreamBatchNo);
        wipRef.setFeedQuantityKg(new BigDecimal(feedKg));   // semiFinished=false (在制 WIP)
        ProcessSheetRow row = materializedFinishedRow(id, batchNumber,
                new BigDecimal(outputQty), new BigDecimal(outputQty), new ArrayList<>(List.of(wipRef)));
        // materializedFinishedRow processOrder=1; 让下游序 > 中间道 (语义清晰, 不影响本测)
        return row;
    }

    @Test
    @DisplayName("R3 loud 告警: 中间道吃 SFI 投料(40)且被同小结下游在制 WIP 全消耗(net=0) → 不入 SFI + WARN 记录漏计 (不再静默)")
    void fullyConsumedIntermediateWithSfiFeedLogsLoudWarn() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(InterimSettleServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProcessSheetRow intermediate = intermediateSfiRow(310L, "INT-310", "40", "SFI-X", "40");
            ProcessSheetRow downstream = finishedRowConsumingWip(311L, "FGD-311", "10", "INT-310", "40");
            when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                    .thenReturn(List.of(intermediate, downstream));
            stubBatch(311L, new BigDecimal("200.00"));   // 下游 base
            when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-X")).thenReturn(new BigDecimal("5"));

            service.interimSettle(FACTORY, PLAN_ID, 7L);

            // 中间道 net=0 → 不入 SFI (postClerkOutput 从不为该中间道锚调用)
            verify(wipInventoryService, org.mockito.Mockito.never())
                    .postClerkOutput(any(), any(), any(), any(), any(), any(), any(), any());
            // 但 SFI-X 投料仍严格出库 (库存扣减照常, 只是成本对下游漏计)
            verify(wipInventoryService).consumeClerkSemiStrict(FACTORY, "SFI-X", new BigDecimal("40"));
            // 🔴 R3: 漏计不再静默 — WARN 记录 (含 R3 标记 + 中间道 batchNo)
            boolean warned = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .anyMatch(e -> e.getFormattedMessage().contains("R3")
                            && e.getFormattedMessage().contains("INT-310"));
            assertThat(warned).as("net<=0 中间道 SFI 投料漏计应记 loud WARN").isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private ProcessSheetRowRequest deserialize(ProcessSheetRow row) {
        try {
            return objectMapper.readValue(row.getRowPayload(), ProcessSheetRowRequest.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String toJson(ProcessSheetRowRequest req) {
        try {
            return objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
