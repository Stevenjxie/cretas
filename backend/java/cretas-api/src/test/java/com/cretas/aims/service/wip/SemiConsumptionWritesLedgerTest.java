package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 半成品<b>领用必须写一条 OUT 流水</b>，不能只改余额。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>2026-08-17 在 F006 生产端到端走查实测：整条链走通之后，
 * {@code semi_finished_inventory_transactions} 里<b>只有 1 行 IN，一行 OUT 都没有</b>——
 * 而那一轮发生过<b>两次领用</b>（第②道领 2.00kg、第③道领 1.50kg）。
 * 余额确实扣了（{@code consumed} 从 0 → 2.00 → DEPLETED），
 * 但<b>流水里查不到是谁领走的</b>。
 *
 * <p>后果：半成品<b>只有进账没有出账</b> ⇒ 对不了账、追不到「这批料被哪道工序领走」、
 * 盘点差异无从溯源。
 *
 * <h2>这不是设计遗漏，是没接上（形态 B）</h2>
 *
 * <p>{@link SemiFinishedInventoryTransaction.TxnType#OUT} 的语义在实体 javadoc 里
 * <b>写得很完整</b>：「下道领用 / 二次加工 (OUT: 负数量)」，
 * {@code sourceRef} 说明「OUT → 下道 intermediateBatchNo」，
 * {@code quantity} 说明「IN 为正; OUT/REVERSE 为负」。
 * 枚举里 {@link SemiFinishedInventoryTransaction.SourceType#SECONDARY_CONSUME} 也在。
 * <b>只是全仓没有任何地方写过 OUT 行。</b>
 *
 * <p>⇒ 机制在、契约在、没接上。单测对这一类是盲的：它直接调被测函数，绕过了「谁调它」。
 */
@DisplayName("半成品领用必须留下 OUT 流水（不能只改余额）")
class SemiConsumptionWritesLedgerTest {

    /** 与 {@code WipInventoryServiceImpl.consumeSourceWip} 同口径的余额推进（仅供断言参照）。 */
    private static BigDecimal expectedBalance(BigDecimal produced, BigDecimal consumedBefore,
                                              BigDecimal input, BigDecimal adjustment) {
        return produced.subtract(consumedBefore.add(input)).add(adjustment);
    }

    @Test
    @DisplayName("🔴 领用之后, 必须存在一条 OUT 流水, 且数量为负、余额与库存行一致")
    void consumptionMustWriteNegativeOutRow() {
        SemiFinishedInventory sfi = new SemiFinishedInventory();
        sfi.setId(331L);
        sfi.setFactoryId("F006");
        sfi.setIntermediateBatchNo("CLK-SEMI-d0c18d36-2f00956d");
        sfi.setProducedQuantity(new BigDecimal("2.00"));
        sfi.setConsumedQuantity(BigDecimal.ZERO);
        sfi.setAvailableQuantity(new BigDecimal("2.00"));
        sfi.setAdjustmentQuantity(BigDecimal.ZERO);
        sfi.setUnit("kg");
        sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);

        ProductionReport report = new ProductionReport();
        report.setId(23799L);
        report.setFactoryId("F006");

        WorkProcessTask task = new WorkProcessTask();
        task.setId(1786L);
        task.setFactoryId("F006");
        task.setProcessOrder(2);

        BigDecimal input = new BigDecimal("2.00");

        SemiFinishedInventoryTransaction out = WipLedgerEntries.consumptionRow(
                sfi, input, report, task, 1311L);

        assertThat(out).as("领用必须产生一条流水行, 不能只改余额").isNotNull();
        assertThat(out.getTxnType()).isEqualTo(SemiFinishedInventoryTransaction.TxnType.OUT);
        assertThat(out.getSourceType())
                .isEqualTo(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME);
        assertThat(out.getQuantity())
                .as("OUT 按实体 javadoc 必须是负数量")
                .isEqualByComparingTo(input.negate());
        assertThat(out.getBalanceAfter())
                .as("余额快照必须与库存行推进后的可用量一致")
                .isEqualByComparingTo(expectedBalance(
                        new BigDecimal("2.00"), BigDecimal.ZERO, input, BigDecimal.ZERO));
        assertThat(out.getReportId()).isEqualTo(23799L);
        assertThat(out.getSemiFinishedId()).isEqualTo(331L);
    }

    /**
     * 🔴 接线断言 —— 上面两条只证明「这一行【构造】得对」，不证明「它被【保存】了」。
     *
     * <p>2026-08-17 实测：把 {@code WipInventoryServiceImpl} 里的
     * {@code txnRepo.save(outTxn)} 变异掉（包成 {@code if (false)}），
     * 上面两条<b>依然 2/2 全绿</b> —— 因为它们直接调纯函数，绕过了「谁调它」。
     * 这正是本仓反复记的那条：<b>测了 helper 不是测接线</b>。
     *
     * <p>所以必须有一条走真实 service 的断言。
     */
    @Test
    @DisplayName("🔴 接线: 走真实 service 领用后, txnRepo 必须收到一条 OUT 行")
    void serviceActuallyPersistsTheOutRow() {
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        SemiFinishedInventoryTransactionRepository txnRepo =
                mock(SemiFinishedInventoryTransactionRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);

        SemiFinishedInventory sfi = new SemiFinishedInventory();
        sfi.setId(331L);
        sfi.setFactoryId("F006");
        sfi.setIntermediateBatchNo("CLK-SEMI-d0c18d36-2f00956d");
        sfi.setProducedQuantity(new BigDecimal("2.00"));
        sfi.setConsumedQuantity(BigDecimal.ZERO);
        sfi.setAvailableQuantity(new BigDecimal("2.00"));
        sfi.setAdjustmentQuantity(BigDecimal.ZERO);
        sfi.setUnit("kg");
        sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);

        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                eq("F006"), eq("CLK-SEMI-d0c18d36-2f00956d"))).thenReturn(Optional.of(sfi));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(reportRepo.findYieldReportsByTask(anyString(), any())).thenReturn(List.of());

        WipInventoryServiceImpl svc = new WipInventoryServiceImpl(
                wipRepo, txnRepo, reportRepo,
                mock(BatchLineageEdgeRepository.class), taskRepo,
                mock(WorkProcessRepository.class), mock(ProductTypeRepository.class),
                mock(ProductFamilyResolver.class), mock(ApplicationEventPublisher.class));

        ProductionReport report = new ProductionReport();
        report.setId(23799L);
        report.setFactoryId("F006");
        report.setSourceWipNo("CLK-SEMI-d0c18d36-2f00956d");
        report.setInputQuantity(new BigDecimal("2.00"));
        report.setInputUnit("kg");
        report.setOutputKind("FINISHED");   // 只验领用侧, 不触发半成品入账分支

        WorkProcessTask task = new WorkProcessTask();
        task.setId(1786L);
        task.setFactoryId("F006");
        task.setProductionBatchId(10759L);
        task.setProcessOrder(2);

        svc.postApprovedOutput("F006", report, task, 1311L);

        ArgumentCaptor<SemiFinishedInventoryTransaction> cap =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo, atLeastOnce()).save(cap.capture());

        SemiFinishedInventoryTransaction out = cap.getAllValues().stream()
                .filter(t -> SemiFinishedInventoryTransaction.TxnType.OUT.equals(t.getTxnType()))
                .findFirst().orElse(null);
        assertThat(out)
                .as("走真实 service 领用之后, 必须有一条 OUT 流水被保存 —— "
                        + "只改余额不记流水 = 半成品只有进账没有出账")
                .isNotNull();
        assertThat(out.getQuantity()).isEqualByComparingTo(new BigDecimal("-2.00"));
        assertThat(out.getReportId()).isEqualTo(23799L);

        // 🔴 这一条是补上来的。第一版只断言了 quantity 和 reportId, 漏了【派生出来的】
        //    balanceAfter —— 而真正错的就是它: 流水行在字段已被修改【之后】构造,
        //    consumed 已经是 2.00, 算出 -2.00, 生产上真落了一条 balance_after=-2.000000。
        //    「修复要按最不显眼的那个验收」—— 显眼的 quantity 早就对了。
        assertThat(out.getBalanceAfter())
                .as("领用 2.00 之后余额应当是 0.00 —— 若为负说明流水行是在字段改完之后构造的")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("阴性对照: 领用量为 0 / null 时不得凭空造流水")
    void noRowWhenNothingConsumed() {
        SemiFinishedInventory sfi = new SemiFinishedInventory();
        sfi.setId(331L);
        sfi.setFactoryId("F006");
        sfi.setProducedQuantity(new BigDecimal("2.00"));
        sfi.setConsumedQuantity(BigDecimal.ZERO);
        sfi.setAvailableQuantity(new BigDecimal("2.00"));
        sfi.setAdjustmentQuantity(BigDecimal.ZERO);

        ProductionReport report = new ProductionReport();
        report.setId(1L);
        report.setFactoryId("F006");
        WorkProcessTask task = new WorkProcessTask();
        task.setId(1L);

        assertThat(WipLedgerEntries.consumptionRow(sfi, BigDecimal.ZERO, report, task, 1L))
                .as("领 0 不该产生流水").isNull();
        assertThat(WipLedgerEntries.consumptionRow(sfi, null, report, task, 1L))
                .as("领 null 不该产生流水").isNull();
    }
}
