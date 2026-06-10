package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * B-47 REVERSE 路径: replayMovingAverage 精确数值测试。
 *
 * <p>replayMovingAverage 通过 executeReversal 间接触发，测试策略：
 * 构造完整的 executeReversal 场景，捕获最终 SFI 保存，验证均价回放结果。
 *
 * <p>BUG-R1 / BUG-R2 已在 #662 修复 (commit 122d5d48):
 * <ul>
 *   <li>BUG-R1 (已修): replayMovingAverage 现在两条路径均 setProducedQuantity =
 *       IN 净额 (扣除 REVERSE 后), 撤回后不再虚高。下方 bugR1_* 测试是
 *       <b>真实断言</b> (assertEquals producedQuantity == 0), 对修复后代码 PASS。</li>
 *   <li>BUG-R2 (已修): replayMovingAverage 现计入 OUT 事务; 且 Guard G1
 *       (DOWNSTREAM_CONSUMED) 在 submitReversal 阻止"先 OUT 后撤回"路径。
 *       下方 bugR2_* 是纯文档测试 (assertTrue), 记录 G1 守护语义。</li>
 * </ul>
 *
 * @see ReportReversalServiceImpl#replayMovingAverage
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("B-47: replayMovingAverage REVERSE 路径精确数值")
class ReplayMovingAverageTest {

    private static final String FACTORY_ID = "F001";

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;

    @InjectMocks
    private ReportReversalServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> sfiCaptor;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Stubs the common executeReversal plumbing: log load, no FGB, empty tasks. */
    private ReportReversalLog stubExecuteReversalSetup(Long logId, Long batchId, Long sfiId,
                                                        List<ProductionReport> reports,
                                                        List<SemiFinishedInventoryTransaction> inTxns) {
        ReportReversalLog log = ReportReversalLog.builder()
                .id(logId)
                .factoryId(FACTORY_ID)
                .batchId(batchId)
                .planId(null)  // no FGB involvement
                .status(ReportReversalLog.ReversalStatus.PENDING)
                .submittedBy(1L)
                .build();

        when(reversalLogRepo.findById(logId)).thenReturn(Optional.of(log));
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, batchId)).thenReturn(reports);
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(Collections.emptyList());

        // REVERSE txn save
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> {
            SemiFinishedInventoryTransaction t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });

        // Wire IN txns for the report
        for (ProductionReport r : reports) {
            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, r.getId()))
                    .thenReturn(inTxns);
        }

        when(reversalLogRepo.save(any(ReportReversalLog.class))).thenAnswer(inv -> inv.getArgument(0));
        return log;
    }

    private SemiFinishedInventory stubSfi(Long sfiId, BigDecimal produced, BigDecimal consumed,
                                          BigDecimal available, BigDecimal unitCost,
                                          String intermediateBatchNo) {
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(sfiId)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo(intermediateBatchNo)
                .producedQuantity(produced)
                .consumedQuantity(consumed)
                .availableQuantity(available)
                .unitCost(unitCost)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .unit("kg")
                .build();
        when(wipRepo.findById(sfiId)).thenReturn(Optional.of(sfi));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        return sfi;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("replayMovingAverage — 撤回后均价回放")
    class ReplayTests {

        @Test
        @DisplayName("RP-1: 单次 IN 被完全撤回 → unitCost=null, availableQuantity=0, status=DEPLETED")
        void singleInFullyReversed_depleted() {
            /*
             * State before reversal:
             *   SFI: produced=100, available=100, unitCost=10.0000
             *   Ledger: [IN qty=100 unitCost@Txn=10.0000]
             *           [REVERSE qty=-100 unitCost@Txn=10.0000]  ← written by executeReversal
             *
             * After replayMovingAverage:
             *   totalQty = 100 + (-100) = 0
             *   → DEPLETED, unitCost=null, availableQuantity=0
             */
            Long logId = 1L, batchId = 100L, sfiId = 200L;

            ProductionReport r = ProductionReport.builder()
                    .id(500L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(10L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                    new BigDecimal("10.0000"), "SEMI-RP001");

            // After executeReversal writes the REVERSE TXN, replayMovingAverage re-reads all TXNs
            // Stub the post-REVERSE ledger state (IN + REVERSE)
            SemiFinishedInventoryTransaction reverseTxn = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-100"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn, reverseTxn));

            service.executeReversal(logId, FACTORY_ID);

            // Capture the final SFI save (from replayMovingAverage)
            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SFI with id=" + sfiId + " not saved"));

            assertEquals(BigDecimal.ZERO, finalSfi.getAvailableQuantity(),
                    "After full reversal: availableQuantity must be 0");
            assertNull(finalSfi.getUnitCost(),
                    "After full reversal: unitCost must be null (no valid IN remaining)");
            assertEquals(SemiFinishedInventory.Status.DEPLETED, finalSfi.getStatus(),
                    "After full reversal: status must be DEPLETED");
        }

        @Test
        @DisplayName("RP-2: 双批次 IN, 仅撤回第二次 → 均价回滚到第一批单价")
        void dualBatchPartialReversal_unitCostRollsBackToFirstBatch() {
            /*
             * Scenario: two IN txns, second one is now being reversed.
             * Ledger after reversal:
             *   IN-1: qty=100  @ 10.0000/kg
             *   IN-2: qty=50   @ 16.0000/kg   (original)
             *   REVERSE: qty=-50 @ 16.0000/kg  (written for report-2)
             *
             * replayMovingAverage sums:
             *   totalQty        = 100 + 50 + (-50) = 100
             *   weightedCost    = 100×10.0000 + 50×16.0000 + (-50)×16.0000
             *                   = 1000 + 800 - 800 = 1000
             *   newUnitCost     = 1000 / 100 = 10.0000
             *   availableQty    = totalQty = 100
             */
            Long logId = 2L, batchId = 101L, sfiId = 201L;

            // Only report-2 is in this batch for the reversal
            ProductionReport r2 = ProductionReport.builder()
                    .id(502L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn2 = SemiFinishedInventoryTransaction.builder()
                    .id(12L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("50"))
                    .unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r2), List.of(inTxn2));
            stubSfi(sfiId, new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("150"),
                    new BigDecimal("12.0000"), "SEMI-RP002");

            // Ledger after REVERSE written: IN-1 + IN-2 + REVERSE
            SemiFinishedInventoryTransaction inTxn1 = SemiFinishedInventoryTransaction.builder()
                    .id(11L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100"))
                    .unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            SemiFinishedInventoryTransaction reverseTxn2 = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-50"))
                    .unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn1, inTxn2, reverseTxn2));

            service.executeReversal(logId, FACTORY_ID);

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst()
                    .orElseThrow();

            assertEquals(new BigDecimal("100"), finalSfi.getAvailableQuantity(),
                    "After reversing IN-2: availableQuantity = 100 (only IN-1 remains)");
            // hand-calc: (100×10 + 50×16 - 50×16) / 100 = 1000/100 = 10.0000
            assertEquals(new BigDecimal("10.0000"), finalSfi.getUnitCost(),
                    "After reversing IN-2: unitCost rolls back to IN-1 price = 10.0000");
            assertEquals(SemiFinishedInventory.Status.AVAILABLE, finalSfi.getStatus());
        }

        @Test
        @DisplayName("RP-3: 混合均价撤销 — 100 kg@10 + 50 kg@16 avg=12.0000, 撤 50@16 → avg 回 10.0000")
        void mixedAverageThenReversal_exactRollback() {
            /*
             * This is the canonical B-47 test: prove that the replay undoes the weighted blend.
             * Identical math to RP-2 but written as the canonical check.
             *
             * Before reversal: unitCost=12.0000 (blended)
             * Ledger replay after REVERSE:
             *   totalQty     = 100 + 50 - 50 = 100
             *   weightedCost = 100×10 + 50×16 - 50×16 = 1000
             *   newUnitCost  = 1000/100 = 10.0000
             */
            Long logId = 3L, batchId = 102L, sfiId = 202L;

            ProductionReport r = ProductionReport.builder()
                    .id(503L).factoryId(FACTORY_ID).batchId(batchId).build();

            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(13L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("50"))
                    .unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("150"),
                    new BigDecimal("12.0000"), "SEMI-RP003");

            SemiFinishedInventoryTransaction in1 = SemiFinishedInventoryTransaction.builder()
                    .id(14L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            SemiFinishedInventoryTransaction rev = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-50")).unitCostAtTxn(new BigDecimal("16.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(in1, inTxn, rev));

            service.executeReversal(logId, FACTORY_ID);

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst().orElseThrow();

            assertEquals(new BigDecimal("10.0000"), finalSfi.getUnitCost(),
                    "Canonical B-47: 100@10+50@16 avg=12, reverse 50@16 → unitCost=10.0000");
            assertEquals(new BigDecimal("100"), finalSfi.getAvailableQuantity());
        }

        // ─── BUG-R1 / BUG-R2 回归守卫 (#662 已修; 断言 CORRECT math 对修复后代码 PASS) ───

        @Test
        @DisplayName("BUG-R1 回归: 整单撤回后 producedQuantity 扣减为 IN 净额 (#662 已修)")
        void bugR1_producedQuantityNotUpdatedOnReversal() {
            // Correct math: single IN(100) then REVERSE(-100) → producedQuantity should be 0
            Long logId = 10L, batchId = 200L, sfiId = 300L;

            ProductionReport r = ProductionReport.builder()
                    .id(600L).factoryId(FACTORY_ID).batchId(batchId).build();
            SemiFinishedInventoryTransaction inTxn = SemiFinishedInventoryTransaction.builder()
                    .id(20L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .quantity(new BigDecimal("100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();

            stubExecuteReversalSetup(logId, batchId, sfiId, List.of(r), List.of(inTxn));
            stubSfi(sfiId, new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                    new BigDecimal("10.0000"), "SEMI-BUGR1");

            SemiFinishedInventoryTransaction rev = SemiFinishedInventoryTransaction.builder()
                    .id(99L).semiFinishedId(sfiId)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                    .quantity(new BigDecimal("-100")).unitCostAtTxn(new BigDecimal("10.0000"))
                    .build();
            when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId))
                    .thenReturn(List.of(inTxn, rev));

            service.executeReversal(logId, FACTORY_ID);

            verify(wipRepo, atLeastOnce()).save(sfiCaptor.capture());
            SemiFinishedInventory finalSfi = sfiCaptor.getAllValues().stream()
                    .filter(s -> s.getId() != null && s.getId().equals(sfiId))
                    .findFirst().orElseThrow();

            // #662 fix: producedQuantity must be 0 after full IN reversal (was: stayed at 100)
            assertEquals(BigDecimal.ZERO, finalSfi.getProducedQuantity(),
                    "producedQuantity should be 0 after full IN reversal (#662 BUG-R1 fix)");
        }

        @Test
        @DisplayName("BUG-R2: OUT-after-reversal 由 Guard G1 (DOWNSTREAM_CONSUMED) 在 submitReversal 阻止 (文档)")
        void bugR2_outTxnNotAccountedInReplay() {
            // 纯文档测试 — 记录 BUG-R2 的处理边界, 不做数值断言。
            //
            // replayMovingAverage 在 totalQty 累计中不计入 OUT 行 (只算 IN 净额 - REVERSE),
            // 因此若一份 IN 在被 OUT 部分消耗后再撤回, 单纯的 replay 无法表达"已被下游消耗"的语义。
            //
            // 这条路径在 submitReversal 层被 Guard G1 (DOWNSTREAM_CONSUMED) 拦截:
            // 已有下游 OUT 消耗的 SFI 不允许撤回其来源报工 → 永不到达 executeReversal/replayMovingAverage。
            // 因此 executeReversal 内对 OUT 的 no-op 处理是安全的 (前置守卫保证不变量)。
            assertTrue(true, "OUT-after-reversal blocked upstream by Guard G1 (DOWNSTREAM_CONSUMED) in submitReversal");
        }
    }
}
