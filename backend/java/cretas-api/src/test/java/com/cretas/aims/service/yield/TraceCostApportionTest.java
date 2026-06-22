package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * traceCost 按消耗比例分摊 — 防共享上游双重计数 (SP-B1).
 *
 * <p>核心断言: 一个父批次(成本¥600, 产出100kg)被两个下游各消耗50kg,
 * 每个下游得到¥300, 合计¥600 —— 不是¥1200。
 * <p>向后兼容断言: 1:1 全量消耗(消耗100kg, 产出100kg)→ 得到全部¥600, 与改前一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("traceCost 按消耗比例分摊 (SP-B1)")
class TraceCostApportionTest {

    private static final String F = "DEMO_FACTORY";
    private static final String ORDER = "SO-APPORTION-TEST";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionReportRepository productionReportRepository;
    @Mock private YieldReportService yieldReportService;
    @InjectMocks private OrderCostBreakdownService service;

    // ─── helpers ────────────────────────────────────────────────────────────

    private ProductionPlan plan(String id) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        return p;
    }

    private ProductionBatch batch(Long id) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setQuantity(new BigDecimal("100"));
        return b;
    }

    /** MaterialConsumption that consumes a given upstream MaterialBatch id. */
    private MaterialConsumption cons(String upstreamMbId, String qty, String totalCost) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(F);
        c.setBatchId(upstreamMbId);
        c.setQuantity(new BigDecimal(qty));
        c.setUnitPrice(BigDecimal.ZERO);
        c.setTotalCost(new BigDecimal(totalCost));
        return c;
    }

    /**
     * WIP MaterialBatch: produced by a production batch (sourceDocType=PRODUCTION_BATCH),
     * with known receiptQuantity (total output of that upstream batch).
     */
    private MaterialBatch wipMb(String id, long srcProdBatchId, String receiptQty) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(F);
        mb.setBatchNumber(id);
        mb.setQuantityUnit("kg");
        mb.setSourceDocType("PRODUCTION_BATCH");
        mb.setSourceDocId(String.valueOf(srcProdBatchId));
        mb.setReceiptQuantity(new BigDecimal(receiptQty));
        return mb;
    }

    /** Leaf MaterialBatch (raw material — no sourceDocType). */
    private MaterialBatch leafMb(String id) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(F);
        mb.setBatchNumber(id);
        mb.setQuantityUnit("kg");
        // sourceDocType intentionally null → leaf
        return mb;
    }

    /** Stub a single production batch with no labor/seasoning/packaging yield data. */
    private void stubSingleBatch(Long batchId, List<MaterialConsumption> consumptions) {
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER))
                .thenReturn(List.of(plan("PP-TEST")));
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(F), any()))
                .thenReturn(List.of(batch(batchId)));
        // No yield report (labor/seasoning/packaging = 0)
        when(yieldReportService.getYield(F, batchId))
                .thenReturn(BatchYieldDTO.builder()
                        .totalLaborCost(BigDecimal.ZERO)
                        .steps(List.of(StepYieldDTO.builder().processOrder(1).materialCost(BigDecimal.ZERO).build()))
                        .build());
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(batchId, F))
                .thenReturn(consumptions);
    }

    // ─── tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("两个下游各消耗父批一半(50kg) → 父成本(¥600)不被双计, 各得¥300, 合计¥600")
    void diamond_noDoubleCount() {
        /*
         * Topology:
         *   父 WIP batch (prod batch 100, output 100kg, cost ¥600 from leaf MB-RAW)
         *     ├── 下游 A  (prod batch 1, consumes 50kg of MB-WIP → expects ¥300)
         *     └── 下游 B  (prod batch 2, consumes 50kg of MB-WIP → expects ¥300)
         *
         * 父批的消耗 (leaf): MB-RAW, qty=100kg, totalCost=¥600
         * MB-WIP.receiptQuantity = 100kg  (total output of prod batch 100)
         */

        // --- parent upstream batch setup ---
        MaterialConsumption parentRaw = cons("MB-RAW", "100", "600");
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(100L, F))
                .thenReturn(List.of(parentRaw));
        // MB-RAW is a leaf (raw material, no sourceDocType)
        when(materialBatchRepository.findByIdAndFactoryId("MB-RAW", F))
                .thenReturn(Optional.of(leafMb("MB-RAW")));
        // MB-WIP is the WIP batch produced by prod batch 100, receiptQuantity=100kg
        when(materialBatchRepository.findByIdAndFactoryId("MB-WIP", F))
                .thenReturn(Optional.of(wipMb("MB-WIP", 100L, "100")));

        // ---- downstream A: consumes 50kg of MB-WIP ----
        MaterialConsumption consA = cons("MB-WIP", "50", "300");
        stubSingleBatch(1L, List.of(consA));

        OrderCostBreakdownDTO dtoA = service.compute(F, ORDER, false);
        BigDecimal costA = dtoA.getRawMaterialCost();

        // ---- downstream B: consumes 50kg of MB-WIP (same parent) ----
        MaterialConsumption consB = cons("MB-WIP", "50", "300");
        stubSingleBatch(2L, List.of(consB));

        OrderCostBreakdownDTO dtoB = service.compute(F, ORDER, false);
        BigDecimal costB = dtoB.getRawMaterialCost();

        // Each downstream gets exactly half the parent cost
        assertThat(costA)
                .as("下游A应得父成本的一半: ¥300")
                .isEqualByComparingTo("300.00");
        assertThat(costB)
                .as("下游B应得父成本的一半: ¥300")
                .isEqualByComparingTo("300.00");

        // Total across both downstreams == parent cost (no double-count)
        assertThat(costA.add(costB))
                .as("两下游合计应等于父成本¥600, 不应双计为¥1200")
                .isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("向后兼容: 1:1 全量消耗(100kg consume 100kg parent) → 得到全部¥600 (与改前一致)")
    void fullConsumption_backwardCompat() {
        /*
         * Single downstream consumes all 100kg of MB-WIP (receiptQuantity=100kg).
         * Ratio = 100/100 = 1 → apportioned == upstreamSum = ¥600 → unchanged.
         */
        MaterialConsumption parentRaw = cons("MB-RAW", "100", "600");
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(200L, F))
                .thenReturn(List.of(parentRaw));
        when(materialBatchRepository.findByIdAndFactoryId("MB-RAW-2", F))
                .thenReturn(Optional.of(leafMb("MB-RAW-2")));
        // Re-use leafMb for parentRaw's upstream batch (MB-RAW is a leaf)
        when(materialBatchRepository.findByIdAndFactoryId("MB-RAW", F))
                .thenReturn(Optional.of(leafMb("MB-RAW")));

        MaterialBatch wipFull = wipMb("MB-WIP-FULL", 200L, "100");
        when(materialBatchRepository.findByIdAndFactoryId("MB-WIP-FULL", F))
                .thenReturn(Optional.of(wipFull));

        // Single downstream consumes all 100kg
        MaterialConsumption fullCons = cons("MB-WIP-FULL", "100", "600");
        stubSingleBatch(3L, List.of(fullCons));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getRawMaterialCost())
                .as("1:1 全量消耗应得到全部父成本¥600 (向后兼容)")
                .isEqualByComparingTo("600.00");
    }
}
