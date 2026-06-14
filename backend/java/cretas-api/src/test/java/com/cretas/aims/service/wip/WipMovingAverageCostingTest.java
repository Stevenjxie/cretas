package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B-47: SemiFinishedInventory 移动加权均价精确数值测试 (V0→V1).
 *
 * <p>覆盖场景:
 * <ol>
 *   <li>单批次 IN — unitCost = totalCost / qty, scale-4 HALF_UP</li>
 *   <li>双批次混合加权 — 100 kg@¥10 + 50 kg@¥16 → avg ¥12.0000</li>
 *   <li>非整除混合加权 — 手算验证 HALF_UP scale-4 精度</li>
 *   <li>消耗后再次入库的 rolling 重算</li>
 *   <li>零数量 semiOutputQuantity 跳过 (R4 backward compat)</li>
 *   <li>成本 null 时 unitCost 诚实 null</li>
 *   <li>upsertProducedWip 路径: 首次 IN 建行, unitCost = accumulatedCost / produced</li>
 *   <li>upsertProducedWip 路径: 第二次 IN 叠加 (积累成本 / 总产出)</li>
 * </ol>
 *
 * <p>REVERSE 回放路径 (replayMovingAverage) 见 {@link ReplayMovingAverageTest}.
 *
 * <p>已知 BUG 详见本文件末尾 BUG REPORT 注释 + @Disabled 断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("B-47: SemiFinishedInventory 移动加权均价精确数值")
class WipMovingAverageCostingTest {

    private static final String FACTORY_ID = "F001";

    @Mock
    private SemiFinishedInventoryRepository wipRepo;
    @Mock
    private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock
    private ProductionReportRepository reportRepo;
    @Mock
    private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock
    private WorkProcessTaskRepository taskRepo;
    @Mock
    private WorkProcessRepository workProcessRepo;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> sfiCaptor;
    @Captor
    private ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor;

    // ─────────────────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────────────────

    private WorkProcessTask task(long taskId, long batchId, int order) {
        return WorkProcessTask.builder()
                .id(taskId)
                .factoryId(FACTORY_ID)
                .productionBatchId(batchId)
                .productTypeId("PT-" + batchId)
                .processOrder(order)
                .plannedUnit("kg")
                .build();
    }

    private ProductionReport semiReport(long reportId, long taskId, long batchId,
                                        String semiCode, BigDecimal qty, BigDecimal labor, BigDecimal material) {
        return ProductionReport.builder()
                .id(reportId)
                .factoryId(FACTORY_ID)
                .batchId(batchId)
                .workProcessTaskId(taskId)
                .outputKind("SEMI")
                .semiCode(semiCode)
                .semiOutputQuantity(qty)
                .semiOutputUnit("kg")
                .laborCost(labor)
                .materialCost(material)
                .build();
    }

    /**
     * W8 BUG-SP1-NEW-ROW 修复后 first-IN 流程: findForUpdate(empty) → ensureSemiRowExists 建 0 量占位行
     * (saveAndFlush) → 再 findForUpdate 拿占位行 → applyMovingAverageIn 累加 → save。
     *
     * <p>mock 用一个可变占位 holder: saveAndFlush 捕获占位行+赋 id 存入 holder; 第二次 findForUpdate
     * 返回同一占位对象 (后续被 applyMovingAverageIn 原地累加, 最终 save 捕获到全量累加值, 与历史断言一致)。
     */
    private void stubFirstIn(String semiCode) {
        // Idempotency keyed on report_id now (BUG-GOLD-RERUN-WEIGHTED-AVG-SKIP fix); each test posts one
        // fresh report → no prior IN txn for it → proceed. anyLong() since helper is semiCode-scoped.
        when(txnRepo.findByFactoryIdAndReportId(org.mockito.ArgumentMatchers.eq(FACTORY_ID),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Collections.emptyList());
        final SemiFinishedInventory[] holder = new SemiFinishedInventory[1];
        // 第一次 findForUpdate 返 empty (触发建占位行); 之后返回占位行 (holder 不为 null 时)
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, semiCode))
                .thenAnswer(inv -> Optional.ofNullable(holder[0]));
        // ensureSemiRowExists 的子事务 insert: 捕获占位行, 赋 id, 存入 holder
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId(9999L);
            holder[0] = s;
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(9999L);
            }
            return s;
        });
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * F1 修复后 FINISHED 路径 first-IN 流程: findForUpdate(empty) → ensureRowExists 建 0 量占位行 (saveAndFlush)
     * → 再 findForUpdate 拿占位行 → upsertProducedWip 累加 (accumulatedCost / produced) → save。
     *
     * <p>FINISHED 路径不写 ledger txn / 不查 idempotency, 故无 txnRepo 桩 (与 SEMI 的 {@link #stubFirstIn} 区别)。
     */
    private void stubFinishedFirstIn(String wipNo) {
        final SemiFinishedInventory[] holder = new SemiFinishedInventory[1];
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, wipNo))
                .thenAnswer(inv -> Optional.ofNullable(holder[0]));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            s.setId(9998L);
            holder[0] = s;
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(9998L);
            }
            return s;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nested: postSemiOutputLedger path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("postSemiOutputLedger — SEMI 产出登记路径")
    class PostSemiOutputLedger {

        @Test
        @DisplayName("SC-1: 单批次 IN — unitCost = totalCost / qty, scale-4 HALF_UP")
        void singleBatchIn_unitCostIsDirectDivision() {
            // Hand-calc: labor=500, material=1000, qty=100 → unitCost = 1500/100 = 15.0000
            WorkProcessTask t = task(1001L, 2001L, 1);
            ProductionReport r = semiReport(3001L, 1001L, 2001L, "SEMI-A001",
                    new BigDecimal("100"),
                    new BigDecimal("500"),
                    new BigDecimal("1000"));
            stubFirstIn("SEMI-A001");

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("100"), saved.getProducedQuantity(),
                    "producedQuantity must equal semiOutputQuantity for first IN");
            assertEquals(new BigDecimal("100"), saved.getAvailableQuantity(),
                    "availableQuantity = produced - consumed(0) = 100");
            assertEquals(new BigDecimal("1500"), saved.getAccumulatedCost(),
                    "accumulatedCost = labor(500) + material(1000) = 1500");
            // hand-calc: 1500 / 100 = 15.0000
            assertEquals(new BigDecimal("15.0000"), saved.getUnitCost(),
                    "unitCost = 1500/100 = 15.0000 (scale-4 HALF_UP)");
        }

        @Test
        @DisplayName("SC-2: 双批次混合加权 — 100 kg@¥10 + 50 kg@¥16 → avg ¥12.0000")
        void doubleBatch_movingAverageExact() {
            /*
             * Hand-calc:
             *   Batch-1: 100 kg, totalCost=1000 → unitCost=10.0000
             *   Batch-2:  50 kg, totalCost=800  → inUnitCost=16.0000
             *   newProduced = 100+50 = 150
             *   moving avg = (100×10.0000 + 50×16.0000) / 150
             *              = (1000 + 800) / 150
             *              = 1800 / 150
             *              = 12.0000
             */
            WorkProcessTask t = task(1002L, 2002L, 1);
            ProductionReport r2 = semiReport(3002L, 1002L, 2002L, "SEMI-A002",
                    new BigDecimal("50"),
                    new BigDecimal("600"),
                    new BigDecimal("200")); // totalCost=800, inUnitCost=16.0000

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 3002L))
                    .thenReturn(java.util.Collections.emptyList());

            // Existing SFI: batch-1 already posted: 100 kg @ 10.0000/kg
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(5001L)
                    .factoryId(FACTORY_ID)
                    .intermediateBatchNo("SEMI-A002")
                    .producedQuantity(new BigDecimal("100"))
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100"))
                    .unitCost(new BigDecimal("10.0000"))
                    .accumulatedCost(new BigDecimal("1000.00"))
                    .unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-A002"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r2, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("150"), saved.getProducedQuantity(),
                    "producedQuantity = 100 + 50 = 150");
            assertEquals(new BigDecimal("150"), saved.getAvailableQuantity(),
                    "availableQuantity = 150 - 0 = 150");
            // hand-calc: 12.0000 exactly
            assertEquals(new BigDecimal("12.0000"), saved.getUnitCost(),
                    "moving average (100×10 + 50×16)/150 = 1800/150 = 12.0000");
        }

        @Test
        @DisplayName("SC-3: 非整除混合加权 — 100 kg@¥10 + 50 kg@¥13 → avg ¥11.0000")
        void doubleBatch_movingAverageNonTrivial() {
            /*
             * Hand-calc:
             *   Existing: 100 kg @ 10.0000
             *   New IN:    50 kg, totalCost=650 → inUnitCost=650/50=13.0000
             *   moving avg = (100×10.0000 + 50×13.0000) / 150
             *              = (1000 + 650) / 150
             *              = 1650 / 150
             *              = 11.0000 (exact)
             */
            WorkProcessTask t = task(1003L, 2003L, 2);
            ProductionReport r = semiReport(3003L, 1003L, 2003L, "SEMI-A003",
                    new BigDecimal("50"),
                    new BigDecimal("450"),
                    new BigDecimal("200")); // totalCost=650, inUnitCost=13.0000

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 3003L))
                    .thenReturn(java.util.Collections.emptyList());
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(5002L).factoryId(FACTORY_ID).intermediateBatchNo("SEMI-A003")
                    .producedQuantity(new BigDecimal("100")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100")).unitCost(new BigDecimal("10.0000"))
                    .accumulatedCost(new BigDecimal("1000.00")).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-A003"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            // hand-calc: (1000 + 650) / 150 = 1650/150 = 11.0000
            assertEquals(new BigDecimal("11.0000"), saved.getUnitCost(),
                    "moving avg (100×10 + 50×13)/150 = 1650/150 = 11.0000");
        }

        @Test
        @DisplayName("SC-4: 非整除移动均价 HALF_UP scale-4 — 精度验证")
        void movingAverage_halfUpScale4_fractional() {
            /*
             * Hand-calc:
             *   Existing: 3 kg @ 10.0000/kg  (totalCost=30)
             *   New IN:    2 kg, totalCost=25 → inUnitCost=25/2=12.5000
             *   moving avg = (3×10.0000 + 2×12.5000) / 5
             *              = (30 + 25) / 5
             *              = 55 / 5
             *              = 11.0000
             *
             * Choose a case that actually requires HALF_UP:
             *   Existing: 10 kg @ 10.0000/kg  (totalCost=100)
             *   New IN:    3 kg, totalCost=40 → inUnitCost=40/3=13.3333 (HALF_UP scale-4)
             *   moving avg = (10×10.0000 + 3×13.3333) / 13
             *              = (100 + 39.9999) / 13
             *              = 139.9999 / 13
             *              = 10.7692 (HALF_UP scale-4: 139.9999/13 = 10.76922... → 10.7692)
             *
             * NB: inUnitCost is calculated inside postSemiOutputLedger as:
             *   totalCost.divide(inQty, 4, HALF_UP) = 40.divide(3, 4, HALF_UP) = 13.3333
             *   (13.33333... rounds to 13.3333 HALF_UP)
             * Then:
             *   oldQty×oldUnitCost = 10 × 10.0000 = 100.0000
             *   inQty×inUnitCost   =  3 × 13.3333 = 39.9999
             *   sum = 139.9999
             *   divide by 13 (scale 4, HALF_UP): 139.9999 / 13 = 10.769223... → 10.7692
             */
            WorkProcessTask t = task(1004L, 2004L, 1);
            ProductionReport r = semiReport(3004L, 1004L, 2004L, "SEMI-A004",
                    new BigDecimal("3"),
                    new BigDecimal("20"),
                    new BigDecimal("20")); // totalCost=40, inUnitCost=40/3=13.3333

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 3004L))
                    .thenReturn(java.util.Collections.emptyList());
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(5003L).factoryId(FACTORY_ID).intermediateBatchNo("SEMI-A004")
                    .producedQuantity(new BigDecimal("10")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("10")).unitCost(new BigDecimal("10.0000"))
                    .accumulatedCost(new BigDecimal("100.00")).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-A004"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            // hand-calc: inUnitCost=13.3333, (10×10.0000 + 3×13.3333)/13 = 139.9999/13 = 10.7692
            assertEquals(new BigDecimal("10.7692"), saved.getUnitCost(),
                    "Moving avg with fractional inUnitCost: (10×10 + 3×13.3333)/13 = 139.9999/13 = 10.7692");
        }

        @Test
        @DisplayName("SC-5: 有消耗后再次入库 — availableQuantity 正确扣除已消耗量")
        void afterConsumption_secondIn_availableAccountsForConsumed() {
            /*
             * State: produced=100, consumed=30, available=70, unitCost=10.0000
             * New IN: 50 kg @ totalCost=750 → inUnitCost=15.0000
             * After:
             *   newProduced = 100+50 = 150
             *   consumed stays 30
             *   newAvailable = 150-30 = 120
             *   moving avg = (100×10.0000 + 50×15.0000) / 150
             *              = (1000 + 750) / 150 = 1750/150 = 11.6667 (HALF_UP)
             */
            WorkProcessTask t = task(1005L, 2005L, 2);
            ProductionReport r = semiReport(3005L, 1005L, 2005L, "SEMI-A005",
                    new BigDecimal("50"),
                    new BigDecimal("500"),
                    new BigDecimal("250")); // totalCost=750, inUnitCost=15.0000

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 3005L))
                    .thenReturn(java.util.Collections.emptyList());
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(5004L).factoryId(FACTORY_ID).intermediateBatchNo("SEMI-A005")
                    .producedQuantity(new BigDecimal("100"))
                    .consumedQuantity(new BigDecimal("30"))
                    .availableQuantity(new BigDecimal("70"))
                    .unitCost(new BigDecimal("10.0000"))
                    .accumulatedCost(new BigDecimal("1000.00")).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-A005"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("150"), saved.getProducedQuantity(), "produced = 100+50");
            assertEquals(new BigDecimal("120"), saved.getAvailableQuantity(),
                    "available = newProduced(150) - consumed(30) = 120");
            // hand-calc: (100×10.0000 + 50×15.0000)/150 = 1750/150 = 11.6667 (HALF_UP)
            assertEquals(new BigDecimal("11.6667"), saved.getUnitCost(),
                    "moving avg (100×10 + 50×15)/150 = 1750/150 = 11.6667 HALF_UP");
        }

        @Test
        @DisplayName("SC-6: semiOutputQuantity <= 0 → 跳过 (R4 backward compat), 无 SFI/TXN 写入")
        void zeroOrNegativeQty_skips() {
            WorkProcessTask t = task(1006L, 2006L, 1);
            ProductionReport r = semiReport(3006L, 1006L, 2006L, "SEMI-A006",
                    BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("200"));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo, never()).save(any());
            verify(txnRepo, never()).save(any());
        }

        @Test
        @DisplayName("SC-7: 成本全 null → unitCost 诚实 null (无虚假 0 值)")
        void nullCost_unitCostIsNull() {
            WorkProcessTask t = task(1007L, 2007L, 1);
            ProductionReport r = semiReport(3007L, 1007L, 2007L, "SEMI-A007",
                    new BigDecimal("50"), null, null); // no cost data

            stubFirstIn("SEMI-A007");

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertNull(saved.getUnitCost(),
                    "unitCost must be null when all cost inputs are null (no false 0)");
            assertNull(saved.getAccumulatedCost(),
                    "accumulatedCost must be null when all cost inputs are null");
        }

        @Test
        @DisplayName("SC-8: 仅 laborCost 有值, materialCost null → totalCost = laborCost")
        void laborOnlyNullMaterial_totalIsLabor() {
            /*
             * Hand-calc: labor=300, material=null, qty=60 → unitCost=300/60=5.0000
             */
            WorkProcessTask t = task(1008L, 2008L, 1);
            ProductionReport r = semiReport(3008L, 1008L, 2008L, "SEMI-A008",
                    new BigDecimal("60"), new BigDecimal("300"), null);

            stubFirstIn("SEMI-A008");

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("5.0000"), saved.getUnitCost(),
                    "unitCost = laborOnly(300) / qty(60) = 5.0000");
        }

        @Test
        @DisplayName("SC-9: IN ledger TXN 记录 unitCostAtTxn = 本道 inUnitCost")
        void inTxn_unitCostAtTxnMatchesInUnitCost() {
            /*
             * Hand-calc: labor=200, material=300, qty=100 → inUnitCost=500/100=5.0000
             * The TXN row must carry unitCostAtTxn=5.0000 (immutable snapshot).
             */
            WorkProcessTask t = task(1009L, 2009L, 1);
            ProductionReport r = semiReport(3009L, 1009L, 2009L, "SEMI-A009",
                    new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"));

            stubFirstIn("SEMI-A009");

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(txnRepo).save(txnCaptor.capture());
            SemiFinishedInventoryTransaction txn = txnCaptor.getValue();
            assertEquals(SemiFinishedInventoryTransaction.TxnType.IN, txn.getTxnType());
            assertEquals(new BigDecimal("5.0000"), txn.getUnitCostAtTxn(),
                    "TXN.unitCostAtTxn = (200+300)/100 = 5.0000");
            assertEquals(new BigDecimal("100"), txn.getQuantity());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nested: upsertProducedWip path (legacy/FINISHED)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("upsertProducedWip — 传统 FINISHED 产出路径")
    class UpsertProducedWip {

        @Test
        @DisplayName("SC-10: 首次 FINISHED IN — unitCost = accumulatedCost / producedQuantity")
        void firstFinishedIn_unitCost() {
            /*
             * Hand-calc: labor=30, material=45, qty=75 → accumulated=75, unitCost=75/75=1.0000
             */
            WorkProcessTask t = task(2001L, 3001L, 2);
            ProductionReport r = ProductionReport.builder()
                    .id(4001L).factoryId(FACTORY_ID).batchId(3001L).workProcessTaskId(2001L)
                    .outputKind("FINISHED")
                    .outputQuantity(new BigDecimal("75")).outputUnit("kg")
                    .laborCost(new BigDecimal("30")).materialCost(new BigDecimal("45"))
                    .build();

            // F1 修复后 FINISHED 新行也走 ensure-row-then-lock: findForUpdate(empty) → saveAndFlush 占位 → 再 findForUpdate 拿占位 → 累加
            stubFinishedFirstIn("PT-3001-B3001-S2-2001");

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("75"), saved.getProducedQuantity());
            assertEquals(new BigDecimal("75"), saved.getAccumulatedCost());
            // hand-calc: 75/75=1.0000
            assertEquals(new BigDecimal("1.0000"), saved.getUnitCost(),
                    "upsertProducedWip: unitCost = accumulated(75)/produced(75) = 1.0000");
        }

        @Test
        @DisplayName("SC-11: 第二次 FINISHED IN — 累积成本 / 总产出, 均价正确滚动")
        void secondFinishedIn_accumulatesAndRecalculates() {
            /*
             * Existing: produced=100, accumulated=800 → unitCost=800/100=8.0000
             * New IN: qty=50, labor=100, material=150 → totalCost=250
             * After: produced=150, accumulated=1050 → unitCost=1050/150=7.0000
             */
            WorkProcessTask t = task(2002L, 3002L, 3);
            ProductionReport r = ProductionReport.builder()
                    .id(4002L).factoryId(FACTORY_ID).batchId(3002L).workProcessTaskId(2002L)
                    .outputKind("FINISHED")
                    .outputQuantity(new BigDecimal("50")).outputUnit("kg")
                    .laborCost(new BigDecimal("100")).materialCost(new BigDecimal("150"))
                    .build();

            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(6001L).factoryId(FACTORY_ID)
                    .intermediateBatchNo("PT-3002-B3002-S3-2002")
                    .producedQuantity(new BigDecimal("100"))
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100"))
                    .accumulatedCost(new BigDecimal("800.00"))
                    .unitCost(new BigDecimal("8.0000"))
                    .unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            // F1 修复后 FINISHED 既有行走 findForUpdate 悲观锁 (非 findBy)
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PT-3002-B3002-S3-2002"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("150"), saved.getProducedQuantity());
            // accumulated = 800 + 250 = 1050
            assertEquals(new BigDecimal("1050.00"), saved.getAccumulatedCost(),
                    "accumulatedCost = 800 + 250 = 1050.00");
            // hand-calc: 1050/150 = 7.0000
            assertEquals(new BigDecimal("7.0000"), saved.getUnitCost(),
                    "upsertProducedWip rolling: 1050/150 = 7.0000");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nested: R4 honest-null moving-average — 一侧"有量但成本未知"不得稀释
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * R4 (🔴红线) — 半成品移动均价缺成本 honest-null 守卫。
     *
     * <p><b>历史 bug</b>: {@code applyMovingAverageIn} 把缺失 unitCost (null) 当 ¥0 参与加权平均,
     * 一侧"有量但成本未知"时 newUnitCost 被稀释 (e.g. 老 100kg@¥10 + 新 50kg@null → 假 ¥6.67),
     * 违反"缺成本→null 不退化 0"红线。
     *
     * <p><b>修复</b>: 只有两侧成本对各自正数量都已知 (oldKnown && inKnown) 才算混合单价, 否则诚实 null。
     * 占位行 oldQty=0 视为 oldKnown (0 量无成本贡献), 保证 first-IN 字节一致。
     *
     * <p>这些场景走 SEMI ledger 路径触发 {@code applyMovingAverageIn} (通过 existing-row 悲观锁分支)。
     */
    @Nested
    @DisplayName("R4: 移动均价缺成本 honest-null 守卫 (一侧有量无成本不得稀释)")
    class HonestNullMovingAverage {

        @Test
        @DisplayName("R4-1: 老行有成本 (100kg@¥10) + 新 IN 有量无成本 (50kg@null) → unitCost 诚实 null (非 6.67)")
        void existingCostKnown_inHasQtyNoCost_yieldsNull() {
            // 老 100kg@¥10 (accumulated 1000), 新 IN 50kg 无 labor/material → inUnitCost=null
            // BUG: (100×10 + 50×0)/150 = 1000/150 = 6.6667 (假摊薄)
            // FIX: 新侧有量(50>0)但成本未知 → 整体 newUnitCost = null
            WorkProcessTask t = task(4101L, 4001L, 1);
            ProductionReport r = semiReport(4501L, 4101L, 4001L, "SEMI-R4-1",
                    new BigDecimal("50"), null, null); // 无成本 → inUnitCost=null

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 4501L))
                    .thenReturn(java.util.Collections.emptyList());
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(7001L).factoryId(FACTORY_ID).intermediateBatchNo("SEMI-R4-1")
                    .producedQuantity(new BigDecimal("100")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100")).unitCost(new BigDecimal("10.0000"))
                    .accumulatedCost(new BigDecimal("1000.00")).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-R4-1"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("150"), saved.getProducedQuantity(), "produced 仍累加 100+50");
            assertNull(saved.getUnitCost(),
                    "[R4 红线] 新侧有量(50kg)但成本未知 → unitCost 诚实 null, 不得稀释成 6.6667");
            // accumulatedCost 保持 null-safe 累加: 老 1000 + 本次 totalCost(null) = 1000 (不变)
            assertEquals(new BigDecimal("1000.00"), saved.getAccumulatedCost(),
                    "accumulatedCost null-safe 累加: 1000 + null = 1000");
        }

        @Test
        @DisplayName("R4-2: 老行有量无成本 (100kg@null) + 新 IN 有成本 (50kg@¥16) → unitCost 诚实 null (非 5.33)")
        void existingHasQtyNoCost_inCostKnown_yieldsNull() {
            // 老 100kg 无成本 (unitCost=null, accumulated=null), 新 IN 50kg totalCost=800 → inUnitCost=16
            // BUG: (100×0 + 50×16)/150 = 800/150 = 5.3333 (假摊薄, 把老 100kg 当 ¥0)
            // FIX: 老侧有量(100>0)但成本未知 → 整体 newUnitCost = null
            WorkProcessTask t = task(4102L, 4002L, 1);
            ProductionReport r = semiReport(4502L, 4102L, 4002L, "SEMI-R4-2",
                    new BigDecimal("50"), new BigDecimal("600"), new BigDecimal("200")); // totalCost=800, inUnitCost=16

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 4502L))
                    .thenReturn(java.util.Collections.emptyList());
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(7002L).factoryId(FACTORY_ID).intermediateBatchNo("SEMI-R4-2")
                    .producedQuantity(new BigDecimal("100")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100")).unitCost(null)   // 有量无成本
                    .accumulatedCost(null).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-R4-2"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("150"), saved.getProducedQuantity(), "produced 仍累加 100+50");
            assertNull(saved.getUnitCost(),
                    "[R4 红线] 老侧有量(100kg)但成本未知 → unitCost 诚实 null, 不得把老量当 ¥0 稀释成 5.3333");
            // accumulatedCost null-safe 累加: 老 null + 本次 totalCost(600+200=800) = 800 (无 scale, compareTo 比值)
            assertEquals(0, new BigDecimal("800").compareTo(saved.getAccumulatedCost()),
                    "accumulatedCost null-safe 累加: null + 800 = 800");
        }

        @Test
        @DisplayName("R4-3 (回归): 两侧成本都已知 (100kg@¥10 + 50kg@¥16) → 正确加权 12.0000 (修复不破坏正常路径)")
        void bothCostKnown_correctWeightedAverage() {
            // 回归保护: 两侧都已知 → 仍 (100×10 + 50×16)/150 = 12.0000 (与 SC-2 同, 字节一致)
            WorkProcessTask t = task(4103L, 4003L, 1);
            ProductionReport r = semiReport(4503L, 4103L, 4003L, "SEMI-R4-3",
                    new BigDecimal("50"), new BigDecimal("600"), new BigDecimal("200")); // totalCost=800, inUnitCost=16

            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 4503L))
                    .thenReturn(java.util.Collections.emptyList());
            SemiFinishedInventory existing = SemiFinishedInventory.builder()
                    .id(7003L).factoryId(FACTORY_ID).intermediateBatchNo("SEMI-R4-3")
                    .producedQuantity(new BigDecimal("100")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("100")).unitCost(new BigDecimal("10.0000"))
                    .accumulatedCost(new BigDecimal("1000.00")).unit("kg")
                    .status(SemiFinishedInventory.Status.AVAILABLE).build();
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-R4-3"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("12.0000"), saved.getUnitCost(),
                    "[R4 回归] 两侧成本都已知 → 正确加权 (100×10 + 50×16)/150 = 12.0000");
        }

        @Test
        @DisplayName("R4-4 (回归): 占位行 first-IN (oldQty=0) + 新 IN 有成本 → unitCost = inUnitCost (字节一致, 0量不算未知)")
        void placeholderZeroQty_inCostKnown_yieldsInUnitCost() {
            // 占位行 oldQty=0/oldUnitCost=null 视为 oldKnown (0 量无成本贡献) → first-IN 仍 = inUnitCost。
            // labor=500 material=1000 qty=100 → inUnitCost=15.0000; oldQty=0 → newUnitCost=15.0000。
            WorkProcessTask t = task(4104L, 4004L, 1);
            ProductionReport r = semiReport(4504L, 4104L, 4004L, "SEMI-R4-4",
                    new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("1000"));
            stubFirstIn("SEMI-R4-4");

            service.postApprovedOutput(FACTORY_ID, r, t, 10L);

            verify(wipRepo).save(sfiCaptor.capture());
            SemiFinishedInventory saved = sfiCaptor.getValue();
            assertEquals(new BigDecimal("15.0000"), saved.getUnitCost(),
                    "[R4 回归] 占位行 oldQty=0 不算未知成本 → first-IN newUnitCost = inUnitCost = 15.0000");
        }
    }
}
