package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.LaborSegment;
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
import com.cretas.aims.exception.BusinessException;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G3 小结 (interim-settle) 服务级 TDD 测试 — 固化跨小结 SFI in/out + FG + 会话幂等扣减口径。
 *
 * <p>这是 Task 3 「先写测试固化预期再实现」的核心契约。模拟典型库存生产链:
 * <pre>
 *   道1(CLK-W-1, output 100) → 道2(CLK-W-2, feed 100, output 80) → 道3(CLK-W-3, feed 80, output 60, 终点半成品)
 *   道4(CLK-B-1, finished, feed 60 from 道3, output 50, productWeight 9kg)
 * </pre>
 *
 * <p><b>固化的跨小结净行为</b>:
 * <ul>
 *   <li>小结1 (录道1-3): 道1/道2 被同小结道2/道3 消耗 = 瞬态 → 不入 SFI; 道3 终点 → SFI IN 60。
 *       原料扣减 1 笔 (会话幂等)。</li>
 *   <li>小结2 (录道4): 道4 消耗道3 (前序小结已入库) → SFI OUT 60; 道4 成品 → FG 入库 (productWeight 9kg)。</li>
 *   <li>重复点小结 (小结3): 无未结产出行/消耗行 → 0 SFI in/out, 0 FG, 0 扣减 (幂等)。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterimSettleServiceTest - G3 小结跨会话 SFI in/out + FG + 幂等")
class InterimSettleServiceTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN1";
    private static final String PRODUCT_TYPE = "PT1";

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

    private static final BigDecimal LABOR_RATE = new BigDecimal("20");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterimSettleServiceImpl service;

    /** 模拟持久化的小结记录 (跨多次 interimSettle 调用累积). */
    private final List<ProductionInterimSettlement> savedSettlements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new InterimSettleServiceImpl(
                planRepository, rowRepository, consumptionRepository, materialBatchRepository,
                settlementRepository, finishedGoodsBatchRepository, productTypeRepository,
                wipInventoryService, finishedGoodsFeedService, warehouseResolver, objectMapper,
                batchRepository, clerkProcessEntryService,
                productWorkProcessRepository, workProcessRepository);

        // 工时单价解析 (纯 SFI 中间道人工现算用); 默认按 payload laborSegments 现算真实人工。
        when(clerkProcessEntryService.resolveLaborRate(eq(FACTORY), any())).thenReturn(LABOR_RATE);
        when(clerkProcessEntryService.computeLaborCost(any(), any())).thenReturn(BigDecimal.ZERO);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PP-001");
        plan.setProductTypeId(PRODUCT_TYPE);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        // 小结记录"持久化"模拟: saveAndFlush 累积; findTop 返最大 seq; findAllAsc 返全部
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

        // 行/批次 save 直接返回 (无副作用断言)
        when(rowRepository.save(any(ProcessSheetRow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consumptionRepository.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productTypeRepository.findByIdAndFactoryId(any(), eq(FACTORY))).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn("WH-WKS");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(eq(FACTORY), any()))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // SFI 投料严格扣减: 默认成功返回请求量 (= 实际出库量); 个别测试覆写为抛 (不足/缺失)。
        when(wipInventoryService.consumeClerkSemiStrict(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        // ①c FG 投料严格扣减: 默认成功返回请求量; 个别测试覆写为抛 (不足/缺失)。
        when(finishedGoodsFeedService.consumeForFeedStrict(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("非存货生产 (SAFETY_STOCK) 计划调小结 → 400")
    void rejectsNonStockPlan() {
        ProductionPlan byOrder = new ProductionPlan();
        byOrder.setId(PLAN_ID);
        byOrder.setFactoryId(FACTORY);
        byOrder.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(byOrder));

        assertThatThrownBy(() -> service.interimSettle(FACTORY, PLAN_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅存货生产计划可小结");
    }

    @Test
    @DisplayName("跨小结全链: 小结1 SFI IN 道3, 小结2 SFI OUT 道3 + FG, 小结3 幂等无变化")
    void crossSettlementSfiInOutAndIdempotency() {
        // ── 行对象 (跨调用复用同一实例; 服务会给未结行打 interim_settled_at 戳) ──
        ProcessSheetRow r1 = row(1L, 1, "CLK-W-1", reqNonFinished(1, "CLK-W-1", new BigDecimal("100"), null));
        ProcessSheetRow r2 = row(2L, 2, "CLK-W-2", reqNonFinished(2, "CLK-W-2", new BigDecimal("80"),
                upstream("CLK-W-1", "100")));
        ProcessSheetRow r3 = row(3L, 3, "CLK-W-3", reqNonFinished(3, "CLK-W-3", new BigDecimal("60"),
                upstream("CLK-W-2", "80")));
        ProcessSheetRow r4 = row(4L, 4, "CLK-B-1", reqFinished(4, "CLK-B-1", new BigDecimal("50"),
                new BigDecimal("9"), upstream("CLK-W-3", "60")));

        // 小结1: 仅道1-3 存在且未结
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(r1, r2, r3))   // 小结1
                .thenReturn(List.of(r1, r2, r3, r4)) // 小结2 (道4 加入; 道1-3 已被打戳)
                .thenReturn(List.of(r1, r2, r3, r4)); // 小结3 (全部已结)

        // 小结1 原料消耗: 一笔 raw 100 (来源 RB1); 小结2/3 无未结消耗
        MaterialConsumption rawC = consumption("RB1", new BigDecimal("100"));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>(List.of(rawC)))   // 小结1
                .thenReturn(new ArrayList<>())                 // 小结2
                .thenReturn(new ArrayList<>());                // 小结3
        MaterialBatch rb1 = materialBatch("RB1", new BigDecimal("200"));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("RB1", FACTORY)).thenReturn(Optional.of(rb1));

        // ───────────────── 小结1 ─────────────────
        Map<String, Object> s1 = service.interimSettle(FACTORY, PLAN_ID, 7L);

        assertThat(s1.get("sessionSeq")).isEqualTo(1);
        assertThat(s1.get("deductedConsumptionCount")).isEqualTo(1);
        // 原料扣减: RB1.usedQuantity 0 → 100
        assertThat(rb1.getUsedQuantity()).isEqualByComparingTo("100");
        // SFI IN 仅道3 (道1/道2 同小结内被消耗 → 瞬态不入库)
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), any(), eq(PRODUCT_TYPE), eq(new BigDecimal("60")), any(), eq(null), eq(null));
        @SuppressWarnings("unchecked")
        List<String> semiIn1 = (List<String>) s1.get("semiInBatchNumbers");
        assertThat(semiIn1).containsExactly("CLK-W-3");
        assertThat(s1.get("semiInQuantity")).isEqualTo(new BigDecimal("60"));
        // 小结1 无 SFI OUT, 无 FG
        verify(wipInventoryService, never()).consumeClerkSemi(any(), any(), any());
        verify(finishedGoodsBatchRepository, never()).save(any());
        // 道1-3 已打戳
        assertThat(r1.getInterimSettledAt()).isNotNull();
        assertThat(r3.getInterimSettledAt()).isNotNull();

        // ───────────────── 小结2 ─────────────────
        Map<String, Object> s2 = service.interimSettle(FACTORY, PLAN_ID, 7L);

        assertThat(s2.get("sessionSeq")).isEqualTo(2);
        // SFI OUT: 道4 消耗道3 (前序已入库 CLK-W-3) → consumeClerkSemi 60 (同 anchor)
        ArgumentCaptor<String> anchorIn = ArgumentCaptor.forClass(String.class);
        verify(wipInventoryService).postClerkOutput(eq(FACTORY), anchorIn.capture(),
                eq(PRODUCT_TYPE), eq(new BigDecimal("60")), any(), eq(null), eq(null));
        ArgumentCaptor<String> anchorOut = ArgumentCaptor.forClass(String.class);
        verify(wipInventoryService, times(1)).consumeClerkSemi(eq(FACTORY), anchorOut.capture(),
                eq(new BigDecimal("60")));
        // SFI in 与 out 命中同一运行余额行 (净升降正确的前提)
        assertThat(anchorOut.getValue()).isEqualTo(anchorIn.getValue());
        assertThat(s2.get("semiOutQuantity")).isEqualTo(new BigDecimal("60"));
        // FG 入库: productWeight 9kg
        ArgumentCaptor<FinishedGoodsBatch> fgCap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(fgCap.capture());
        FinishedGoodsBatch fg = fgCap.getValue();
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo("9");
        assertThat(fg.getUnit()).isEqualTo("kg");
        assertThat(fg.getBatchNumber()).isEqualTo("FG-PP-001-S2");
        // 小结2 无新 SFI IN (道4 是成品), postClerkOutput 仍累计 1 次 (来自小结1)
        verify(wipInventoryService, times(1)).postClerkOutput(any(), any(), any(), any(), any(), any(), any());

        // ───────────────── 小结3 (重复点击, 幂等) ─────────────────
        Map<String, Object> s3 = service.interimSettle(FACTORY, PLAN_ID, 7L);

        assertThat(s3.get("sessionSeq")).isEqualTo(3);
        assertThat(s3.get("deductedConsumptionCount")).isEqualTo(0);
        // 累计调用次数不变: postClerkOutput 仍 1, consumeClerkSemi 仍 1, FG save 仍 1
        verify(wipInventoryService, times(1)).postClerkOutput(any(), any(), any(), any(), any(), any(), any());
        verify(wipInventoryService, times(1)).consumeClerkSemi(any(), any(), any());
        verify(finishedGoodsBatchRepository, times(1)).save(any());
        // RB1 未被再次扣减
        assertThat(rb1.getUsedQuantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("部分被同小结消耗: 终点产出60, 同小结下游投40 → SFI IN 净20 (不漏入残留)")
    void partialWithinSessionNetStock() {
        // 道A(CLK-W-A, output 60) + 道B(CLK-B-A, finished, feed 40 from A) 同一小结
        ProcessSheetRow rA = row(10L, 1, "CLK-W-A", reqNonFinished(1, "CLK-W-A", new BigDecimal("60"), null));
        ProcessSheetRow rB = row(11L, 2, "CLK-B-A", reqFinished(2, "CLK-B-A", new BigDecimal("50"),
                null, upstream("CLK-W-A", "40")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rA, rB));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());

        Map<String, Object> s = service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 道A 净结余 = 60 − 40(同小结投B) = 20 → SFI IN 20 (而非整道判瞬态漏入)
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), any(), eq(PRODUCT_TYPE), eq(new BigDecimal("20")), any(), eq(null), eq(null));
        assertThat(s.get("semiInQuantity")).isEqualTo(new BigDecimal("20"));
        @SuppressWarnings("unchecked")
        List<String> semiIn = (List<String>) s.get("semiInBatchNumbers");
        assertThat(semiIn).containsExactly("CLK-W-A");
        // 道B 是成品 → FG, 不入 SFI; CLK-W-A 非前序入库 → 无 SFI OUT
        verify(wipInventoryService, never()).consumeClerkSemi(any(), any(), any());
        verify(finishedGoodsBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("SFI 投料(半成品直接产成品): 成品道吃常驻 SFI → consumeClerkSemi(intermediateBatchNo) 直扣 + FG; 不走 priorStocked anchor")
    void sfiFeedstockDrawsDownStandingSemiAndCreatesFg() {
        // 成品道 (CLK-B-X, finished) 吃一笔常驻半成品库存 "SFI-STANDING-1" feed 30 (无任何前序小结 → 非 priorStocked)。
        // productWeight 6kg → FG 入库; SFI 直接按 intermediateBatchNo 扣减 (不经 per-(plan,pt) anchor)。
        ProcessSheetRow rX = row(20L, 1, "CLK-B-X", reqFinished(1, "CLK-B-X", new BigDecimal("40"),
                new BigDecimal("6"), upstreamSemi("SFI-STANDING-1", "30")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rX));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());

        Map<String, Object> s = service.interimSettle(FACTORY, PLAN_ID, 7L);

        // SFI OUT: 严格扣减 (consumeClerkSemiStrict), 直接按 SFI 的 intermediateBatchNo (非 semiAnchor)
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-STANDING-1"), eq(new BigDecimal("30")));
        // SFI 投料绝不走容忍版 consumeClerkSemi (那条留给 priorStocked anchor 路径)
        verify(wipInventoryService, never()).consumeClerkSemi(any(), any(), any());
        // summary.semiOutQuantity = strict 返回的实际出库量 (= 30, 永不虚高)
        assertThat(s.get("semiOutQuantity")).isEqualTo(new BigDecimal("30"));

        // 成品道 → FG 入库 (productWeight 6kg)
        ArgumentCaptor<FinishedGoodsBatch> fgCap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(fgCap.capture());
        assertThat(fgCap.getValue().getProducedQuantity()).isEqualByComparingTo("6");
        assertThat(fgCap.getValue().getUnit()).isEqualTo("kg");

        // 成品道不入 SFI (postClerkOutput 不被调用)
        verify(wipInventoryService, never()).postClerkOutput(any(), any(), any(), any(), any(), any(), any());
        // 行已打戳
        assertThat(rX.getInterimSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("SFI 投料幂等: 已结成品道重复小结 → 不再二次 consumeClerkSemi")
    void sfiFeedstockIdempotentOnResettle() {
        ProcessSheetRow rX = row(21L, 1, "CLK-B-Y", reqFinished(1, "CLK-B-Y", new BigDecimal("40"),
                new BigDecimal("6"), upstreamSemi("SFI-STANDING-2", "25")));
        // 小结1 该行未结; 小结2 已被打戳 (同一行实例跨调用复用)
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(rX))
                .thenReturn(List.of(rX));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());

        service.interimSettle(FACTORY, PLAN_ID, 7L);
        service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 仅小结1 扣减一次, 小结2 该行已结跳过 (产出侧 interim_settled_at 戳幂等)
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-STANDING-2"), eq(new BigDecimal("25")));
    }

    @Test
    @DisplayName("SFI 投料不足: 小结时 consumeClerkSemiStrict 抛 → 整个小结 loud-fail, 不产 phantom FG")
    void sfiFeedstockInsufficientThrowsLoud() {
        ProcessSheetRow rX = row(23L, 1, "CLK-B-Z", reqFinished(1, "CLK-B-Z", new BigDecimal("40"),
                new BigDecimal("6"), upstreamSemi("SFI-LOW-1", "100")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rX));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        // 库存不足 → strict 抛 (禁止降级)
        when(wipInventoryService.consumeClerkSemiStrict(eq(FACTORY), eq("SFI-LOW-1"), eq(new BigDecimal("100"))))
                .thenThrow(new BusinessException(409, "半成品库存不足: SFI-LOW-1 余30 需100")
                        .withCode("SFI_INSUFFICIENT"));

        // 小结整体抛 → @Transactional 回滚 → 不会静默产 phantom FG
        assertThatThrownBy(() -> service.interimSettle(FACTORY, PLAN_ID, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("半成品库存不足");
    }

    // ─────────────────────────────────────────────────────────────
    // ①c 成品作投料来源 (FG feedstock) — 严格扣减 + 成本传导 + loud-fail
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("①c FG 投料: 成品道吃常驻成品(FG) → consumeForFeedStrict(batchNumber) 直扣 + FG 入库; 不走 SFI/priorStocked 路径")
    void fgFeedstockDrawsDownFinishedGoodsAndCreatesFg() {
        // 成品道 (CLK-B-FG, finished) 吃一笔常驻成品库存 "FG-STANDING-1" feed 30。productWeight 6kg → FG 入库。
        ProcessSheetRow rX = row(120L, 1, "CLK-B-FG", reqFinished(1, "CLK-B-FG", new BigDecimal("40"),
                new BigDecimal("6"), upstreamFg("FG-STANDING-1", "30")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rX));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());

        Map<String, Object> s = service.interimSettle(FACTORY, PLAN_ID, 7L);

        // FG OUT: 严格扣减 (consumeForFeedStrict), 直接按成品 batchNumber, 投料单位 kg
        verify(finishedGoodsFeedService, times(1))
                .consumeForFeedStrict(eq(FACTORY), eq("FG-STANDING-1"), eq(new BigDecimal("30")), eq("kg"));
        // FG 投料绝不走 SFI 路径
        verify(wipInventoryService, never()).consumeClerkSemiStrict(any(), any(), any());
        verify(wipInventoryService, never()).consumeClerkSemi(any(), any(), any());
        // summary.finishedGoodsOutQuantity = strict 返回的实际扣减量 (= 30)
        assertThat(s.get("finishedGoodsOutQuantity")).isEqualTo(new BigDecimal("30"));

        // 成品道 → FG 入库 (productWeight 6kg)
        ArgumentCaptor<FinishedGoodsBatch> fgCap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(fgCap.capture());
        assertThat(fgCap.getValue().getProducedQuantity()).isEqualByComparingTo("6");
        // 成品道不入 SFI
        verify(wipInventoryService, never()).postClerkOutput(any(), any(), any(), any(), any(), any(), any());
        assertThat(rX.getInterimSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("①c FG 投料成本传导: 成品道吃 costed FG(unitCost 20) feed 30 + pb.totalCost 40 → FG.unitCost 含传导成本")
    void fgFeedstockTransmitsCostToFinishedGoods() {
        // 成品道 CLK-B-FGC 吃 FG-COSTED (unitCost 20) feed 30, productWeight 10kg。
        //   pb.totalCost(人工/调料) = 40; FG total = 40 + 30×20(=600) = 640; FG.unitCost = 640/10 = 64。
        ProcessSheetRow rC = row(121L, 1, "CLK-B-FGC", reqFinished(1, "CLK-B-FGC", new BigDecimal("50"),
                new BigDecimal("10"), upstreamFg("FG-COSTED", "30")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rC));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        when(finishedGoodsFeedService.getFeedUnitCost(FACTORY, "FG-COSTED")).thenReturn(new BigDecimal("20"));
        ProductionBatch pbC = new ProductionBatch();
        pbC.setId(121L);
        pbC.setFactoryId(FACTORY);
        pbC.setTotalCost(new BigDecimal("40.00"));
        when(batchRepository.findByIdAndFactoryId(121L, FACTORY)).thenReturn(Optional.of(pbC));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        ArgumentCaptor<FinishedGoodsBatch> fgCap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(fgCap.capture());
        FinishedGoodsBatch fg = fgCap.getValue();
        assertThat(fg.getUnitCost()).isNotNull();
        assertThat(fg.getUnitCost()).isEqualByComparingTo("64"); // (40 + 30×20) / 10
    }

    @Test
    @DisplayName("①c FG 投料成本诚实null: 输入成品 unitCost=null → 产出 FG.unitCost null (不伪造 ¥0)")
    void fgFeedstockNullCostPoisonsToNull() {
        ProcessSheetRow rC = row(122L, 1, "CLK-B-FGN", reqFinished(1, "CLK-B-FGN", new BigDecimal("50"),
                new BigDecimal("10"), upstreamFg("FG-LEGACY", "30")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rC));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        // 旧库存成品: unitCost null (未接通成本)
        when(finishedGoodsFeedService.getFeedUnitCost(FACTORY, "FG-LEGACY")).thenReturn(null);
        ProductionBatch pbC = new ProductionBatch();
        pbC.setId(122L);
        pbC.setFactoryId(FACTORY);
        pbC.setTotalCost(new BigDecimal("40.00"));
        when(batchRepository.findByIdAndFactoryId(122L, FACTORY)).thenReturn(Optional.of(pbC));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        ArgumentCaptor<FinishedGoodsBatch> fgCap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(fgCap.capture());
        // 🔴 诚实 null: 输入成品无成本 → 产出成本未知 (不伪造 ¥0)
        assertThat(fgCap.getValue().getUnitCost()).isNull();
    }

    @Test
    @DisplayName("①c FG 投料不足: 小结时 consumeForFeedStrict 抛 → 整个小结 loud-fail, 不产 phantom FG")
    void fgFeedstockInsufficientThrowsLoud() {
        ProcessSheetRow rX = row(123L, 1, "CLK-B-FGL", reqFinished(1, "CLK-B-FGL", new BigDecimal("40"),
                new BigDecimal("6"), upstreamFg("FG-LOW-1", "100")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rX));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        when(finishedGoodsFeedService.consumeForFeedStrict(eq(FACTORY), eq("FG-LOW-1"), eq(new BigDecimal("100")), eq("kg")))
                .thenThrow(new BusinessException(409, "成品库存不足: FG-LOW-1 余30 需100")
                        .withCode("FG_INSUFFICIENT"));

        assertThatThrownBy(() -> service.interimSettle(FACTORY, PLAN_ID, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成品库存不足");
    }

    @Test
    @DisplayName("option F: 纯 SFI 中间道小结 → 产出入 SFI(postClerkOutput 锚+productType+净量) + 输入 SFI 扣减(consumeClerkSemiStrict)")
    void pureSfiMiddleStepPostsOutputAndDrawsInput() {
        // 道M: 非成品, 纯 SFI 投料 (吃常驻 SFI-IN-A feed 50), 产出 40 → 入本计划 SFI 锚。
        //   batchId=null (option F 不物化 WIP), batchNumber=锚, rowStatus=SAVED_SFI。
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRow rM = pureSfiRow(30L, 1, anchor,
                reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("SFI-IN-A", "50")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());

        Map<String, Object> s = service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 输入 SFI 扣减 (严格版, 禁止降级)
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-IN-A"), eq(new BigDecimal("50")));
        assertThat(s.get("semiOutQuantity")).isEqualTo(new BigDecimal("50"));
        // 产出入 SFI: 锚 = 本计划 per-(plan,productType), 净量 = 40 (无同小结下游消耗)
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(), eq(null), eq(null));
        assertThat(s.get("semiInQuantity")).isEqualTo(new BigDecimal("40"));
        @SuppressWarnings("unchecked")
        List<String> semiIn = (List<String>) s.get("semiInBatchNumbers");
        assertThat(semiIn).containsExactly(anchor);
        // 非成品 → 不进 FG
        verify(finishedGoodsBatchRepository, never()).save(any());
        // 行已打戳 (产出侧幂等)
        assertThat(rM.getInterimSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("option F (单上游道 process-agnostic): 滚揉道(非混锅) SAVED_SFI 纯 SFI 投料 → 小结产出入 SFI + 输入 SFI 扣减 ('从滚揉起步选半成品')")
    void pureSfiSingleUpstreamGunrouStepPostsOutputAndDrawsInput() {
        // 客户 07-01: 从滚揉起步选半成品。滚揉是单上游道 (非熟制/气调混锅), 但 小结 SFI in/out
        //   只看 rowStatus=SAVED_SFI + upstreamSources.semiFinished, 完全 process-agnostic
        //   → 单上游道 SAVED_SFI 行与混锅道走同一路径 (镜像 pureSfiMiddleStepPostsOutputAndDrawsInput)。
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRowRequest req = reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("SFI-IN-GR", "50"));
        req.setProcessCode("gunrou");                          // 单上游道 (滚揉), 非混锅
        ProcessSheetRow rM = pureSfiRow(90L, 1, anchor, req);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());

        Map<String, Object> s = service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 输入 SFI 严格扣减 (禁止降级)
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-IN-GR"), eq(new BigDecimal("50")));
        assertThat(s.get("semiOutQuantity")).isEqualTo(new BigDecimal("50"));
        // 产出入 SFI: 锚 = 本计划 per-(plan,productType), 净量 = 40 (无同小结下游消耗)
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(), eq(null), eq(null));
        assertThat(s.get("semiInQuantity")).isEqualTo(new BigDecimal("40"));
        // 非成品 → 不进 FG
        verify(finishedGoodsBatchRepository, never()).save(any());
        assertThat(rM.getInterimSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("option F 链: 道A(纯SFI, 小结1 产出入 anchorY) → 道B(小结2 吃 anchorY) → SFI Y 先升 60 后降 60 净平")
    void pureSfiChainAcrossSettlements() {
        // 道A: 非成品纯 SFI (吃常驻 SFI-RAW feed 100), 产出 60 → 入 anchorY (本计划 PT1)。
        String anchorY = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRow rA = pureSfiRow(40L, 1, anchorY,
                reqNonFinished(1, anchorY, new BigDecimal("60"), upstreamSemi("SFI-RAW", "100")));
        // 道B: 成品, 吃 道A 产出的 SFI (anchorY, semiFinished=true) feed 60, productWeight 9kg → FG。
        ProcessSheetRow rB = row(41L, 2, "CLK-B-B", reqFinished(2, "CLK-B-B", new BigDecimal("50"),
                new BigDecimal("9"), upstreamSemi(anchorY, "60")));

        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(rA))            // 小结1: 仅道A 未结
                .thenReturn(List.of(rA, rB));        // 小结2: 道A 已结 + 道B 未结
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>())
                .thenReturn(new ArrayList<>());

        // ── 小结1: 道A 产出入 anchorY (+60), 输入 SFI-RAW 严格扣 100 ──
        Map<String, Object> s1 = service.interimSettle(FACTORY, PLAN_ID, 7L);
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-RAW"), eq(new BigDecimal("100")));
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchorY), eq(PRODUCT_TYPE), eq(new BigDecimal("60")), any(), eq(null), eq(null));
        assertThat(s1.get("semiInQuantity")).isEqualTo(new BigDecimal("60"));
        assertThat(rA.getInterimSettledAt()).isNotNull();

        // ── 小结2: 道B 吃 anchorY (-60, 严格扣减), 成品 → FG ──
        Map<String, Object> s2 = service.interimSettle(FACTORY, PLAN_ID, 7L);
        assertThat(s2.get("sessionSeq")).isEqualTo(2);
        // anchorY 被严格扣减 60 (先升后降净平)
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq(anchorY), eq(new BigDecimal("60")));
        // 小结2 无新 SFI IN (道B 成品), postClerkOutput 累计仍 1 次 (仅来自小结1)
        verify(wipInventoryService, times(1)).postClerkOutput(any(), any(), any(), any(), any(), any(), any());
        // 道B 成品 → FG 入库
        verify(finishedGoodsBatchRepository, times(1)).save(any());
    }

    // ─────────────────────────────────────────────────────────────
    // 🔴 成本传导 (G3 SFI cost transmission) — 诚实 null
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("成本传导(a): 纯SFI中间道 (吃 costed SFI + 人工) → postClerkOutput 带真实 unitCost (非 null)")
    void sfiInCarriesRealMovingAverageCost() {
        // 道M: SAVED_SFI, 吃常驻 SFI-A (unitCost=10) feed 50 + 本道人工 100 → 产出 40。
        //   outputTotalCost = 人工100 + 50×10(=500) = 600; outputUnitCost = 600/40 = 15.0000。
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRowRequest req = reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("SFI-A", "50"));
        req.setLaborSegments(List.of(seg("08:00", "13:00", 1)));  // 现算人工 (mock 覆写为 100)
        ProcessSheetRow rM = pureSfiRow(50L, 1, anchor, req);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        // costed 输入 SFI + 本道人工现算
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));
        when(clerkProcessEntryService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("100"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 输入 SFI 扣减 (严格)
        verify(wipInventoryService, times(1))
                .consumeClerkSemiStrict(eq(FACTORY), eq("SFI-A"), eq(new BigDecimal("50")));
        // 产出入 SFI: net 40 @ unitCost 15 (真实成本传导, 非 null)
        ArgumentCaptor<BigDecimal> unitCostCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(),
                unitCostCap.capture(), eq(null));
        assertThat(unitCostCap.getValue()).isNotNull();
        assertThat(unitCostCap.getValue()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("成本传导(b) 链: 半成品A(costed) → 道 → SFI-B → 气调成品(FG) → FG.unitCost 含传导成本")
    void chainTransmitsCostThroughToFinishedGoods() {
        String anchorB = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        // 小结1: 道A (SAVED_SFI) 吃 RAW-SEMI(unitCost 5) feed 100, 无人工 → 产出 80 → 入 anchorB。
        //   outputUnitCost = (0 + 100×5)/80 = 500/80 = 6.25
        ProcessSheetRow rA = pureSfiRow(60L, 1, anchorB,
                reqNonFinished(1, anchorB, new BigDecimal("80"), upstreamSemi("RAW-SEMI", "100")));
        // 小结2: 道C 成品, 吃 anchorB(=半成品B, semiFinished) feed 80, productWeight 10kg → FG。
        //   pb.totalCost(人工/调料) = 40; FG total = 40 + 80×6.25(=500) = 540; FG unitCost = 540/10 = 54。
        ProcessSheetRow rC = row(61L, 2, "CLK-B-C", reqFinished(2, "CLK-B-C", new BigDecimal("50"),
                new BigDecimal("10"), upstreamSemi(anchorB, "80")));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(rA))            // 小结1
                .thenReturn(List.of(rA, rC));        // 小结2
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>()).thenReturn(new ArrayList<>());
        when(wipInventoryService.getSemiUnitCost(FACTORY, "RAW-SEMI")).thenReturn(new BigDecimal("5"));
        // 半成品B 的移动均价 (小结1 postClerkOutput 6.25 累加后; postClerkOutput 已 mock, 故此处代表其结果)
        when(wipInventoryService.getSemiUnitCost(FACTORY, anchorB)).thenReturn(new BigDecimal("6.25"));
        // 道C 成品的 ProductionBatch 成本 (人工/调料, 不含 SFI 投料)
        ProductionBatch pbC = new ProductionBatch();
        pbC.setId(61L);
        pbC.setFactoryId(FACTORY);
        pbC.setTotalCost(new BigDecimal("40.00"));
        when(batchRepository.findByIdAndFactoryId(61L, FACTORY)).thenReturn(Optional.of(pbC));

        // ── 小结1: 道A 产出入 anchorB @ 6.25 ──
        service.interimSettle(FACTORY, PLAN_ID, 7L);
        ArgumentCaptor<BigDecimal> ucA = ArgumentCaptor.forClass(BigDecimal.class);
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchorB), eq(PRODUCT_TYPE), eq(new BigDecimal("80")), any(),
                ucA.capture(), eq(null));
        assertThat(ucA.getValue()).isEqualByComparingTo("6.25");

        // ── 小结2: 道C 成品 → FG.unitCost 含传导成本 = 54 ──
        service.interimSettle(FACTORY, PLAN_ID, 7L);
        ArgumentCaptor<FinishedGoodsBatch> fgCap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(fgCap.capture());
        FinishedGoodsBatch fg = fgCap.getValue();
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo("10");
        assertThat(fg.getUnitCost()).isNotNull();
        assertThat(fg.getUnitCost()).isEqualByComparingTo("54"); // (40 + 80×6.25) / 10
    }

    @Test
    @DisplayName("成本传导(c) 诚实null: 输入 SFI 无成本(unitCost=null) → 产出 unitCost null (不伪造 ¥0), 即便本道有人工")
    void nullCostSfiInputPoisonsOutputToNull() {
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRowRequest req = reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("LEGACY-SFI", "50"));
        req.setLaborSegments(List.of(seg("08:00", "10:00", 2)));  // 有人工, 但输入 SFI 无成本 → 整体诚实 null
        ProcessSheetRow rM = pureSfiRow(70L, 1, anchor, req);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        // 旧库存: 输入 SFI unitCost 为 null (未接通成本)
        when(wipInventoryService.getSemiUnitCost(FACTORY, "LEGACY-SFI")).thenReturn(null);
        when(clerkProcessEntryService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("50"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 🔴 诚实 null: 输入无成本 → postClerkOutput 收到 unitCost = null (不因有人工而伪造部分成本)
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(),
                eq(null), eq(null));
    }

    @Test
    @DisplayName("成本传导(Fix1 #7) 诚实null: 纯SFI调味道(isSeasoningStep) → 产出 null (不漏调料桶=禁止降级), 即便SFI成本+人工皆已知")
    void pureSfiSeasoningStepByFlagReturnsNull() {
        // 纯 SFI 调味道: SFI 投料有成本(10) + 人工有(100), 但调味道调料成本无处可算 → 整道诚实 null。
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRowRequest req = reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("SFI-A", "50"));
        req.setSeasoningStep(true);                             // 前端显式调味标志
        req.setLaborSegments(List.of(seg("08:00", "10:00", 1)));
        ProcessSheetRow rM = pureSfiRow(80L, 1, anchor, req);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));
        when(clerkProcessEntryService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("100"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 🔴 调味道调料桶未知 → null (不降级成 labor-only=(100+500)/40=15 假数据)
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(),
                eq(null), eq(null));
    }

    @Test
    @DisplayName("成本传导(Fix1 #7) 诚实null: 纯SFI调味道(工序名'卤制'匹配) → 产出 null (工序名口径同 buildStepEntry)")
    void pureSfiSeasoningStepByNameReturnsNull() {
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRowRequest req = reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("SFI-A", "50"));
        req.setProcessName("卤制");                              // 工序名匹配 熟/卤/煮/腌/注射/入味/调味
        ProcessSheetRow rM = pureSfiRow(81L, 1, anchor, req);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(),
                eq(null), eq(null));
    }

    @Test
    @DisplayName("成本传导(Fix1 #7): 纯SFI非调味道(工序名'切片'不匹配) → 仍走 labor+SFI 成本 (不误伤非调味道)")
    void pureSfiNonSeasoningStepStillCosted() {
        String anchor = WipInventoryService.clerkSemiAnchor(PLAN_ID, PRODUCT_TYPE);
        ProcessSheetRowRequest req = reqNonFinished(1, anchor, new BigDecimal("40"), upstreamSemi("SFI-A", "50"));
        req.setProcessName("切片");                              // 非调味 → 仍现算成本
        req.setLaborSegments(List.of(seg("08:00", "13:00", 1)));
        ProcessSheetRow rM = pureSfiRow(82L, 1, anchor, req);
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN_ID)).thenReturn(List.of(rM));
        when(consumptionRepository.findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(PLAN_ID, FACTORY))
                .thenReturn(new ArrayList<>());
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));
        when(clerkProcessEntryService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("100"));

        service.interimSettle(FACTORY, PLAN_ID, 7L);

        // 非调味道: (人工100 + 50×10) / 40 = 15 (成本正常传导, 不被 Fix1 误伤)
        ArgumentCaptor<BigDecimal> uc = ArgumentCaptor.forClass(BigDecimal.class);
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), eq(anchor), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(),
                uc.capture(), eq(null));
        assertThat(uc.getValue()).isEqualByComparingTo("15");
    }

    // ─────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────

    /** option F 纯 SFI 中间道行: batchId=null (无 WIP/ProductionBatch), batchNumber=SFI 锚, rowStatus=SAVED_SFI. */
    private ProcessSheetRow pureSfiRow(Long id, int order, String batchNumber, ProcessSheetRowRequest req) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode(req.getProcessCode());
        row.setProcessOrder(order);
        row.setClientRowId("c" + id);
        row.setBatchId(null);
        row.setBatchNumber(batchNumber);
        row.setRowStatus(ProcessSheetRow.STATUS_SAVED_SFI);
        row.setRowPayload(toJson(req));
        return row;
    }

    private ProcessSheetRow row(Long id, int order, String batchNumber, ProcessSheetRowRequest req) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode(req.getProcessCode());
        row.setProcessOrder(order);
        row.setClientRowId("c" + id);
        row.setBatchId(id);
        row.setBatchNumber(batchNumber);
        row.setRowStatus("SAVED");
        row.setRowPayload(toJson(req));
        return row;
    }

    private ProcessSheetRowRequest reqNonFinished(int order, String batchNumber, BigDecimal output,
                                                  List<UpstreamRef> upstream) {
        return baseReq(order, batchNumber, output, false, null, upstream);
    }

    private ProcessSheetRowRequest reqFinished(int order, String batchNumber, BigDecimal output,
                                               BigDecimal productWeight, List<UpstreamRef> upstream) {
        return baseReq(order, batchNumber, output, true, productWeight, upstream);
    }

    private ProcessSheetRowRequest baseReq(int order, String batchNumber, BigDecimal output, boolean finished,
                                           BigDecimal productWeight, List<UpstreamRef> upstream) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("c" + order);
        req.setProcessCode("p" + order);
        req.setProcessOrder(order);
        req.setProductTypeId(PRODUCT_TYPE);
        req.setBatchNumber(batchNumber);
        req.setFinished(finished);
        req.setOutputQuantity(output);
        req.setUnit(finished ? "盒" : "kg");
        req.setProductWeight(productWeight);
        req.setUpstreamSources(upstream);
        return req;
    }

    private LaborSegment seg(String start, String end, int workers) {
        LaborSegment s = new LaborSegment();
        s.setStartTime(start);
        s.setEndTime(end);
        s.setWorkerCount(workers);
        return s;
    }

    private List<UpstreamRef> upstream(String sourceBatchNumber, String feedKg) {
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(sourceBatchNumber);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        return List.of(ref);
    }

    /** SFI 投料来源 (半成品直接产成品): sourceBatchNumber = 常驻 SFI 的 intermediateBatchNo, semiFinished=true。 */
    private List<UpstreamRef> upstreamSemi(String intermediateBatchNo, String feedKg) {
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(intermediateBatchNo);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setSemiFinished(true);
        return List.of(ref);
    }

    /** ①c FG 投料来源 (成品作投料来源): sourceBatchNumber = 常驻成品批号, finishedGoods=true。 */
    private List<UpstreamRef> upstreamFg(String batchNumber, String feedKg) {
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(batchNumber);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
        ref.setFinishedGoods(true);
        return List.of(ref);
    }

    private MaterialConsumption consumption(String sourceBatchId, BigDecimal qty) {
        MaterialConsumption mc = new MaterialConsumption();
        mc.setFactoryId(FACTORY);
        mc.setProductionPlanId(PLAN_ID);
        mc.setBatchId(sourceBatchId);
        mc.setQuantity(qty);
        return mc;
    }

    private MaterialBatch materialBatch(String id, BigDecimal receipt) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(FACTORY);
        mb.setReceiptQuantity(receipt);
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        return mb;
    }

    private String toJson(ProcessSheetRowRequest req) {
        try {
            return objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
