package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionInterimSettlement;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionMode;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
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
    @Mock private WarehouseResolver warehouseResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterimSettleServiceImpl service;

    /** 模拟持久化的小结记录 (跨多次 interimSettle 调用累积). */
    private final List<ProductionInterimSettlement> savedSettlements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new InterimSettleServiceImpl(
                planRepository, rowRepository, consumptionRepository, materialBatchRepository,
                settlementRepository, finishedGoodsBatchRepository, productTypeRepository,
                wipInventoryService, warehouseResolver, objectMapper);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PP-001");
        plan.setProductTypeId(PRODUCT_TYPE);
        plan.setProductionMode(ProductionMode.BY_STOCK);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        // 小结记录"持久化"模拟: save 累积; findTop 返最大 seq; findAllAsc 返全部
        when(settlementRepository.save(any(ProductionInterimSettlement.class)))
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
        when(productTypeRepository.findById(any())).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn("WH-WKS");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(eq(FACTORY), any()))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("非 BY_STOCK 计划调小结 → 400")
    void rejectsNonStockPlan() {
        ProductionPlan byOrder = new ProductionPlan();
        byOrder.setId(PLAN_ID);
        byOrder.setFactoryId(FACTORY);
        byOrder.setProductionMode(ProductionMode.BY_ORDER);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(byOrder));

        assertThatThrownBy(() -> service.interimSettle(FACTORY, PLAN_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅库存");
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

    // ─────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────

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

    private List<UpstreamRef> upstream(String sourceBatchNumber, String feedKg) {
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber(sourceBatchNumber);
        ref.setFeedQuantityKg(new BigDecimal(feedKg));
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
