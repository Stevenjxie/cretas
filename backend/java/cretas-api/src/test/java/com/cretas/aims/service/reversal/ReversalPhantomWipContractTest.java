package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报工冲销不许留下 <b>phantom WIP</b> —— 半成品余额必须由流水账解释得了。
 *
 * <h2>实测（2026-08-18，F006 生产库只读）</h2>
 *
 * <p>先量再改。{@code cretas_prod_db} 的 {@code public} schema 里：
 *
 * <pre>
 * semi_finished_inventory_transactions 全表 8 行:   OUT 4 行 / REVERSE 4 行 / <b>IN 0 行</b>
 * semi_finished_inventory              全表 4 行:   produced = 2.00 / 240.00 / 300.00 / 10.00
 * report_reversal_logs                 全表 14 行:  <b>14 条全部 status=DONE 且 reverted_txn_ids 长度为 0</b>
 * </pre>
 *
 * <p>⇒ 两件事同时成立，而且是同一个根因：
 * <ol>
 *   <li><b>正向入库就没记账</b>。缺的是 <b>IN</b> 方向，不是「冲销时没写反向流水」。
 *       {@code WipInventoryServiceImpl.upsertProducedWip}（FINISHED/legacy 产出）与
 *       {@code postClerkOutput}（逐道录入）都只改余额、不写流水。
 *       最刺眼的是库存行 id=344：{@code produced=10.00}，流水里有一条 {@code REVERSE -10}，
 *       而 {@code IN} 是 0 行 —— <b>冲销了一笔从来没记过账的入库</b>。</li>
 *   <li><b>撤回于是永远是空转</b>。{@code executeReversal} 按 {@code report_id} 找 IN 行来冲销，
 *       找不到就一行不写、照样置 DONE。这个功能 2026-06-10 上线至今
 *       <b>14 次撤回一次都没真正冲销过库存</b>，而每次都告诉用户「撤回完成」。
 *       报工被软删（追溯源没了），它产出的半成品还挂在账上 —— 这就是 phantom WIP。</li>
 * </ol>
 *
 * <h2>⚠️ 与交接件那句结论的出入（说出来，不硬修）</h2>
 *
 * <p>交接件写的是「冲销留 phantom WIP（没有 IN 方向的 SIT 行）」，方向对，但机制不是
 * 「冲销把库存清零了」。实测：<b>撤回根本没碰余额</b>，货是留下的不是清掉的。
 * 「清零」是一个<b>尚未引爆</b>的隐患 —— {@code replayMovingAverage} 把「ΣIN + ΣREVERSE」
 * 当权威净产出<b>回写</b>库存行，净额 ≤ 0 就把 produced/available 清零并置 DEPLETED。
 * 只要某条库存行上出现<b>任何一条</b> IN 流水触发回放，那些从没记过账的产出量就会被抹平
 * （按当前数据：id=343 会从 300 被抹成 0）。本测试把这条隐患也钉住。
 */
@DisplayName("撤回不许留 phantom WIP：半成品余额必须由流水账解释得了")
class ReversalPhantomWipContractTest {

    private static final String FACTORY = "F006";

    // ────────────────────────── 被测装置 ──────────────────────────

    /** 冲销侧装置：把 executeReversal 需要的仓储全部装上。 */
    private static final class ReversalRig {
        final ReportReversalLogRepository logRepo = mock(ReportReversalLogRepository.class);
        final ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        final ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        final SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        final SemiFinishedInventoryTransactionRepository txnRepo =
                mock(SemiFinishedInventoryTransactionRepository.class);
        final FinishedGoodsBatchRepository fgbRepo = mock(FinishedGoodsBatchRepository.class);
        final WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        final List<SemiFinishedInventoryTransaction> saved = new ArrayList<>();
        final ReportReversalServiceImpl service;

        ReversalRig(Long logId, Long batchId, List<ProductionReport> reports,
                    List<SemiFinishedInventoryTransaction> inTxnsForReport) {
            service = new ReportReversalServiceImpl(
                    logRepo, batchRepo, reportRepo, wipRepo, txnRepo, fgbRepo,
                    mock(UserRepository.class), taskRepo,
                    mock(com.cretas.aims.service.NotificationService.class));

            ReportReversalLog log = ReportReversalLog.builder()
                    .id(logId).factoryId(FACTORY).batchId(batchId).planId(null)
                    .status(ReportReversalLog.ReversalStatus.APPROVED)
                    .submittedBy(1309L)
                    .build();
            when(logRepo.findById(logId)).thenReturn(Optional.of(log));
            lenient().when(logRepo.save(any(ReportReversalLog.class))).thenAnswer(i -> i.getArgument(0));
            when(reportRepo.findYieldReportsByBatch(FACTORY, batchId)).thenReturn(reports);
            lenient().when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
            when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, batchId))
                    .thenReturn(Collections.emptyList());
            for (ProductionReport r : reports) {
                lenient().when(txnRepo.findByFactoryIdAndReportId(FACTORY, r.getId()))
                        .thenReturn(inTxnsForReport);
            }
            lenient().when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> {
                SemiFinishedInventoryTransaction t = i.getArgument(0);
                t.setId(9000L + saved.size());
                saved.add(t);
                return t;
            });
            lenient().when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
            lenient().when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), anyLong()))
                    .thenReturn(Collections.emptyList());
        }
    }

    private static SemiFinishedInventory wipRow(Long id, String no, String produced,
                                                String consumed, String available) {
        return SemiFinishedInventory.builder()
                .id(id).factoryId(FACTORY).intermediateBatchNo(no)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .adjustmentQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
    }

    private static ProductionReport yieldReport(Long id, Long batchId) {
        return ProductionReport.builder().id(id).factoryId(FACTORY).batchId(batchId).build();
    }

    // ══════════════════════ 1. 正向入库必须记 IN 流水 ══════════════════════

    /**
     * 🟢 <b>阳性对照</b>。它证明这套探针<b>看得见 IN 流水</b> ——
     * 没有它，后面几条「没有 IN 行」的读数就分不清是「真的没有」还是「我的断言写错了」。
     *
     * <p>同时它是<b>接线断言</b>：走真实入口 {@code postApprovedOutput}，
     * 不是直接调 {@code WipLedgerEntries} 那个纯函数
     * （2026-08-17 的教训：只测 helper，把 {@code txnRepo.save} 包进 {@code if (false)} 也全绿）。
     */
    @Test
    @DisplayName("🟢 阳性对照 + 接线: legacy/FINISHED 产出走真实 service 后必须落一条 IN 流水")
    void productionOutputMustLeaveAnInRow() {
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        SemiFinishedInventoryTransactionRepository txnRepo =
                mock(SemiFinishedInventoryTransactionRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);

        final SemiFinishedInventory[] holder = new SemiFinishedInventory[1];
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenAnswer(i -> Optional.ofNullable(holder[0]));
        when(wipRepo.saveAndFlush(any(SemiFinishedInventory.class))).thenAnswer(i -> {
            SemiFinishedInventory s = i.getArgument(0);
            s.setId(342L);
            holder[0] = s;
            return s;
        });
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(reportRepo.findYieldReportsByTask(anyString(), any())).thenReturn(List.of());
        when(txnRepo.findByFactoryIdAndReportId(anyString(), anyLong())).thenReturn(List.of());

        // ⚠️ 2026-08-18 语义冲突修复: PR #2867 给 WipInventoryServiceImpl 加了两个构造参数
        //    (ProductionWorkflowInstanceRepository / WorkflowTaskPortRepository) 用于画布产出选项。
        //    本文件与 #2867 在【不同文件的不同行】上改动, git 自动合并零冲突, 两个 PR 各自 CI 也全绿
        //    —— 但合到一起编译不过。这类冲突文本层面看不见, 只有【合并后编译一次】才暴露。
        WipInventoryServiceImpl svc = new WipInventoryServiceImpl(
                wipRepo, txnRepo, reportRepo, mock(BatchLineageEdgeRepository.class),
                mock(WorkProcessTaskRepository.class), mock(WorkProcessRepository.class),
                mock(ProductTypeRepository.class),
                mock(ProductionWorkflowInstanceRepository.class),
                mock(WorkflowTaskPortRepository.class),
                mock(ProductFamilyResolver.class),
                mock(ApplicationEventPublisher.class));

        ProductionReport report = ProductionReport.builder()
                .id(23837L).factoryId(FACTORY).batchId(10761L)
                .outputKind(null)                       // legacy —— F006 实际数据全走这条
                .outputQuantity(new BigDecimal("240"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("400"))
                .materialCost(new BigDecimal("720"))
                .build();
        WorkProcessTask task = WorkProcessTask.builder()
                .id(1788L).factoryId(FACTORY).productionBatchId(10761L)
                .productTypeId("eb0aa47b").processOrder(1).plannedUnit("kg")
                .build();

        svc.postApprovedOutput(FACTORY, report, task, 1311L);

        ArgumentCaptor<SemiFinishedInventoryTransaction> cap =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo).save(cap.capture());
        SemiFinishedInventoryTransaction in = cap.getValue();

        assertThat(in.getTxnType())
                .as("产出入库必须留下 IN 流水 —— 只改余额 = 半成品凭空出现, 追溯链断在这里")
                .isEqualTo(SemiFinishedInventoryTransaction.TxnType.IN);
        assertThat(in.getSourceType())
                .isEqualTo(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT);
        assertThat(in.getQuantity()).as("IN 按实体 javadoc 必须是正数量")
                .isEqualByComparingTo(new BigDecimal("240"));
        assertThat(in.getReportId())
                .as("必须挂在产生它的报工上 —— 撤回正是按 report_id 找它")
                .isEqualTo(23837L);
        // 最不显眼的那个: balanceAfter 是派生量, 口径放错边会算出负数 (8-17 生产上真落过 -2.000000)
        assertThat(in.getBalanceAfter())
                .as("余额快照必须与累加后的库存行一致")
                .isEqualByComparingTo(new BigDecimal("240"));
        assertThat(in.getUnitCostAtTxn())
                .as("(400 + 720) / 240 = 4.6667 —— 与生产库 id=342 的 unit_cost 一致")
                .isEqualByComparingTo(new BigDecimal("4.6667"));
    }

    /** 🔵 阴性对照：产出为 0 不许凭空造流水。（没有它，上一条可能只是「每次都写」。） */
    @Test
    @DisplayName("🔵 阴性对照: 产出 0 / null 不得凭空造 IN 流水")
    void noInRowWhenNothingProduced() {
        SemiFinishedInventory sfi = wipRow(342L, "X", "0", "0", "0");
        ProductionReport r = yieldReport(1L, 10761L);
        assertThat(com.cretas.aims.service.wip.WipLedgerEntries
                .productionRow(sfi, BigDecimal.ZERO, null, r, "X", 1L)).isNull();
        assertThat(com.cretas.aims.service.wip.WipLedgerEntries
                .productionRow(sfi, null, null, r, "X", 1L)).isNull();
    }

    // ══════════════════════ 2. 撤回必须真的冲销 ══════════════════════

    /** 🟢 阳性对照：IN 行在 ⇒ 撤回写 REVERSE、余额归零、reverted_txn_ids 非空。 */
    @Test
    @DisplayName("🟢 阳性对照: 有 IN 流水时, 撤回必须写 REVERSE 行并把余额退回")
    void reversalWritesReverseRowWhenLedgerIsComplete() {
        ProductionReport r = yieldReport(23837L, 10761L);
        SemiFinishedInventoryTransaction in = SemiFinishedInventoryTransaction.builder()
                .id(101L).factoryId(FACTORY).semiFinishedId(342L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("240"))
                .unitCostAtTxn(new BigDecimal("4.6667"))
                .reportId(23837L)
                .build();

        ReversalRig rig = new ReversalRig(15L, 10761L, List.of(r), List.of(in));
        SemiFinishedInventory sfi = wipRow(342L, "B10761-S1-1788", "240", "0", "240");
        when(rig.wipRepo.findById(342L)).thenReturn(Optional.of(sfi));
        // 回放读到的是「IN 240 + 刚写的 REVERSE -240」
        when(rig.txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(342L)).thenAnswer(i -> {
            List<SemiFinishedInventoryTransaction> all = new ArrayList<>();
            all.add(in);
            all.addAll(rig.saved);
            return all;
        });

        rig.service.executeReversal(15L, FACTORY);

        SemiFinishedInventoryTransaction rev = rig.saved.stream()
                .filter(t -> SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType()))
                .findFirst().orElse(null);
        assertThat(rev).as("撤回必须写一条 REVERSE 冲销行").isNotNull();
        assertThat(rev.getQuantity()).isEqualByComparingTo(new BigDecimal("-240"));
        assertThat(sfi.getAvailableQuantity())
                .as("冲销之后余额必须退回 0 —— 否则货还挂在账上就是 phantom WIP")
                .isEqualByComparingTo(BigDecimal.ZERO);

        ArgumentCaptor<ReportReversalLog> logCap = ArgumentCaptor.forClass(ReportReversalLog.class);
        verify(rig.logRepo).save(logCap.capture());
        assertThat(logCap.getValue().getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.DONE);
        assertThat(logCap.getValue().getRevertedTxnIds())
                .as("生产库 14 条 reversal_log 这一格全是空的 —— 那正是「撤回什么都没做」的长相")
                .isNotEmpty();
    }

    /**
     * 🔴 <b>本文件的主断言</b>：没有 IN 行、而货还在账上 ⇒ <b>不许</b>报撤回完成。
     *
     * <p>这一条复刻的就是生产库 batch=10761 的实际状态。修复前：撤回静默写 0 行、置 DONE。
     */
    @Test
    @DisplayName("🔴 没有 IN 流水可冲销、半成品还挂在账上 ⇒ 必须报错, ⛔ 不许置 DONE")
    void reversalMustRefuseWhenWipCannotBeReversed() {
        ProductionReport r = yieldReport(23837L, 10761L);
        // 关键: 该报工名下【一条 IN 流水都没有】—— 正是 F006 生产库的实际形态
        ReversalRig rig = new ReversalRig(15L, 10761L, List.of(r), List.of());
        when(rig.wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, 10761L))
                .thenReturn(List.of(wipRow(343L, "B10761-S2-1789", "300", "0", "75")));

        assertThatThrownBy(() -> rig.service.executeReversal(15L, FACTORY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("75")
                .hasMessageContaining("入库");

        assertThat(rig.saved).as("一行流水都写不出来时, 不该硬写").isEmpty();
        verify(rig.logRepo, never()).save(any(ReportReversalLog.class));
    }

    /**
     * 🔵 阴性对照 —— 闸要<b>窄</b>。货已经被下游领空（available = 0）时没有 phantom 可言，
     * 不该拦人。没有这一条，上面那个闸可能是「只要没 IN 行就一律拦」。
     */
    @Test
    @DisplayName("🔵 阴性对照: 没有 IN 流水但余额已是 0 (货已领空) ⇒ 不拦, 正常完成")
    void gateDoesNotFireWhenNothingIsStranded() {
        ProductionReport r = yieldReport(23833L, 10761L);
        ReversalRig rig = new ReversalRig(16L, 10761L, List.of(r), List.of());
        when(rig.wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, 10761L))
                .thenReturn(List.of(wipRow(342L, "B10761-S1-1788", "240", "240", "0")));

        rig.service.executeReversal(16L, FACTORY);

        ArgumentCaptor<ReportReversalLog> logCap = ArgumentCaptor.forClass(ReportReversalLog.class);
        verify(rig.logRepo).save(logCap.capture());
        assertThat(logCap.getValue().getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.DONE);
    }

    // ══════════════════════ 3. 回放不许把没记账的量抹平 ══════════════════════

    /**
     * 🔴 {@code replayMovingAverage} 拿「ΣIN + ΣREVERSE」<b>回写</b>库存行。
     * 流水账缺 IN 行时，这等于把「我没有证据」写成「余额是 0」——
     * 按生产库当前数据，id=343 会从 {@code produced=300} 被抹成 0。
     *
     * <p>禁止降级：算不出就报错，⛔ 不许用 0 糊过去。
     */
    @Test
    @DisplayName("🔴 账面产出 > 流水账 ΣIN ⇒ 回放必须拒绝改写, ⛔ 不许把库存抹平成 0")
    void replayMustRefuseToFlattenUnledgeredStock() {
        ProductionReport r = yieldReport(23837L, 10761L);
        // 这条 IN 只解释了 300 里的 60 —— 剩下 240 是「流水账补全之前」入的库
        SemiFinishedInventoryTransaction partialIn = SemiFinishedInventoryTransaction.builder()
                .id(101L).factoryId(FACTORY).semiFinishedId(343L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("60"))
                .unitCostAtTxn(new BigDecimal("4.2667"))
                .reportId(23837L)
                .build();

        ReversalRig rig = new ReversalRig(17L, 10761L, List.of(r), List.of(partialIn));
        SemiFinishedInventory sfi = wipRow(343L, "B10761-S2-1789", "300", "0", "300");
        when(rig.wipRepo.findById(343L)).thenReturn(Optional.of(sfi));
        when(rig.txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(343L)).thenAnswer(i -> {
            List<SemiFinishedInventoryTransaction> all = new ArrayList<>();
            all.add(partialIn);
            all.addAll(rig.saved);
            return all;
        });

        assertThatThrownBy(() -> rig.service.executeReversal(17L, FACTORY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("300")
                .hasMessageContaining("60");

        assertThat(sfi.getProducedQuantity())
                .as("拒绝之后库存行必须原样不动 —— 抹平 240 才是真正的数据损坏")
                .isEqualByComparingTo(new BigDecimal("300"));
        verify(rig.logRepo, never()).save(any(ReportReversalLog.class));
    }

    /** 🔵 阴性对照：流水账解释得了余额时，回放照常工作（上面那个闸不是「一律拦」）。 */
    @Test
    @DisplayName("🔵 阴性对照: 账面产出 == ΣIN ⇒ 回放正常执行, 闸不响")
    void replayProceedsWhenLedgerExplainsTheBalance() {
        ProductionReport r = yieldReport(23837L, 10761L);
        SemiFinishedInventoryTransaction in = SemiFinishedInventoryTransaction.builder()
                .id(101L).factoryId(FACTORY).semiFinishedId(343L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .quantity(new BigDecimal("300"))
                .unitCostAtTxn(new BigDecimal("4.2667"))
                .reportId(23837L)
                .build();

        ReversalRig rig = new ReversalRig(18L, 10761L, List.of(r), List.of(in));
        SemiFinishedInventory sfi = wipRow(343L, "B10761-S2-1789", "300", "0", "300");
        when(rig.wipRepo.findById(343L)).thenReturn(Optional.of(sfi));
        when(rig.txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(343L)).thenAnswer(i -> {
            List<SemiFinishedInventoryTransaction> all = new ArrayList<>();
            all.add(in);
            all.addAll(rig.saved);
            return all;
        });

        rig.service.executeReversal(18L, FACTORY);

        assertThat(sfi.getProducedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sfi.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
    }

    // ══════════════════════ 4. 拦住人就要给下一步 ══════════════════════

    /** 拦住人的地方必须告诉下一步 —— 只说「不行」而不说「那怎么办」等于把人堵死。 */
    @Test
    @DisplayName("拦截必须带 code + 可执行的下一步 hint")
    void refusalCarriesCodeAndActionableHint() {
        ProductionReport r = yieldReport(23837L, 10761L);
        ReversalRig rig = new ReversalRig(15L, 10761L, List.of(r), List.of());
        when(rig.wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, 10761L))
                .thenReturn(List.of(wipRow(343L, "B10761-S2-1789", "300", "0", "75")));

        BusinessException ex = (BusinessException) org.junit.jupiter.api.Assertions
                .assertThrows(BusinessException.class, () -> rig.service.executeReversal(15L, FACTORY));

        assertThat(ex.getCode()).as("HTTP 语义: 状态冲突").isEqualTo(409);
        assertThat(ex.getErrorCode()).isEqualTo("WIP_NOT_LEDGERED");
        assertThat(ex.getActionHint())
                .as("必须告诉用户下一步做什么, 而不是只说撤不了")
                .isNotBlank()
                .contains("盘点");
        assertThat(ex.getMessage())
                .as("⛔ 不许出现纯英文错误码 / UUID 之类用户看不懂的东西")
                .contains("半成品");
    }
}
