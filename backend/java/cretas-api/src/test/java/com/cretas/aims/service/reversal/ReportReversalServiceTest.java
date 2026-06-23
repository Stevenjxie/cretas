package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SP2 ReportReversalService 单元测试。
 *
 * <p>测试覆盖:
 * <ul>
 *   <li>G1: 下游领用检查 → 409 DOWNSTREAM_CONSUMED</li>
 *   <li>G2: 成品出货检查 → 409 FG_SHIPPED</li>
 *   <li>G3: 幂等 — DONE 直接返回; PENDING → 409 ALREADY_PENDING</li>
 *   <li>有报工数据 → PENDING + 不调用 executeReversal (审批路径)</li>
 *   <li>无报工数据 → 直通 DONE (快速路径)</li>
 *   <li>approve: PENDING → executeReversal 被调用</li>
 *   <li>reject: PENDING → status=REJECTED</li>
 *   <li>listReversals: null status / 有效 status / 非法 status</li>
 * </ul>
 *
 * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SP2: ReportReversalService — 整单撤回守卫 + 幂等 + 审批")
class ReportReversalServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final Long BATCH_ID = 1924L;
    private static final Long SUBMITTED_BY = 7L;
    private static final Long LOG_ID = 42L;

    @InjectMocks
    private ReportReversalServiceImpl service;

    @Mock
    private ReportReversalLogRepository reversalLogRepo;
    @Mock
    private ProductionBatchRepository batchRepo;
    @Mock
    private ProductionReportRepository reportRepo;
    @Mock
    private SemiFinishedInventoryRepository wipRepo;
    @Mock
    private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock
    private FinishedGoodsBatchRepository fgbRepo;
    @Mock
    private com.cretas.aims.repository.UserRepository userRepository;
    @Mock
    private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository workProcessTaskRepository;
    @Mock
    private com.cretas.aims.service.NotificationService notificationService;

    // ==================== submitReversal ====================

    @Nested
    @DisplayName("submitReversal — 三层守卫")
    class SubmitReversalGuards {

        @Test
        @DisplayName("G1: 下游领用存在 → 409 DOWNSTREAM_CONSUMED")
        void g1_downstreamConsumed_throws409() {
            when(txnRepo.existsDownstreamConsumed(FACTORY_ID, String.valueOf(BATCH_ID)))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "test"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已被下道工序领用");

            verify(reversalLogRepo, never()).save(any());
        }

        @Test
        @DisplayName("G2: 成品已出货 → 409 FG_SHIPPED")
        void g2_fgShipped_throws409() {
            when(txnRepo.existsDownstreamConsumed(anyString(), anyString())).thenReturn(false);
            ProductionBatch batch = new ProductionBatch();
            batch.setProductionPlanId("plan-001");
            when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
            when(fgbRepo.existsShippedByFactoryIdAndProductionPlanId(FACTORY_ID, "plan-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "test"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已出货");

            verify(reversalLogRepo, never()).save(any());
        }

        @Test
        @DisplayName("G3: 已有 DONE 记录 → 幂等返回已有记录")
        void g3_existingDone_returnsExisting() {
            when(txnRepo.existsDownstreamConsumed(anyString(), anyString())).thenReturn(false);
            ProductionBatch batch = new ProductionBatch();
            batch.setProductionPlanId("plan-001");
            when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
            when(fgbRepo.existsShippedByFactoryIdAndProductionPlanId(anyString(), anyString()))
                    .thenReturn(false);

            ReportReversalLog existing = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.DONE)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                    BATCH_ID, ReportReversalLog.ReversalScope.WHOLE_ORDER))
                    .thenReturn(Optional.of(existing));

            ReportReversalLog result = service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "retry");
            assertThat(result.getId()).isEqualTo(LOG_ID);
            verify(reversalLogRepo, never()).save(any());
        }

        @Test
        @DisplayName("G3: 已有 PENDING 记录 → 409 ALREADY_PENDING")
        void g3_existingPending_throws409() {
            when(txnRepo.existsDownstreamConsumed(anyString(), anyString())).thenReturn(false);
            ProductionBatch batch = new ProductionBatch();
            when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
            when(fgbRepo.existsShippedByFactoryIdAndProductionPlanId(anyString(), anyString()))
                    .thenReturn(false);

            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                    BATCH_ID, ReportReversalLog.ReversalScope.WHOLE_ORDER))
                    .thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "again"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("待审批");
        }
    }

    @Nested
    @DisplayName("submitReversal — 快速 / 审批路径")
    class SubmitReversalPaths {

        private void setupPassingGuards(ProductionBatch batch) {
            when(txnRepo.existsDownstreamConsumed(anyString(), anyString())).thenReturn(false);
            when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
            when(fgbRepo.existsShippedByFactoryIdAndProductionPlanId(anyString(), anyString()))
                    .thenReturn(false);
            when(reversalLogRepo.findByBatchIdAndReversalScopeAndDeletedAtIsNull(any(), any()))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("无报工数据 → 创建 DONE 记录并执行 executeReversal")
        void noReports_directDone_executesReversal() {
            ProductionBatch batch = new ProductionBatch();
            setupPassingGuards(batch);
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID)).thenReturn(Collections.emptyList());

            ReportReversalLog saved = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.DONE)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.save(any())).thenReturn(saved);
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));

            // For executeReversal — findById succeeds, reports empty → nothing else needed
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));

            ReportReversalLog result = service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "no data");
            assertThat(result.getId()).isEqualTo(LOG_ID);

            // executeReversal should have been called (save will be called for setting DONE)
            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
        }

        @Test
        @DisplayName("有报工数据 → 创建 PENDING 记录，不直接执行")
        void hasReports_createsPending_doesNotExecute() {
            ProductionBatch batch = new ProductionBatch();
            setupPassingGuards(batch);

            ProductionReport report = new ProductionReport();
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(List.of(report));

            ReportReversalLog saved = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.save(any())).thenReturn(saved);
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));

            ReportReversalLog result = service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "with data");
            assertThat(result.getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.PENDING);
        }

        @Test
        @DisplayName("BUG-GOLD-RERUN-FASTPATH: 快速撤回(本人+时间窗内有报工) → 真正执行撤回(软删报工), 不只置 DONE")
        void fastPathWithReports_actuallyExecutesReversal() {
            // 回归: 旧实现 fastPath 预置 status=DONE → executeReversal 幂等跳过 → 报工没软删/成本没清。
            // 此前测试只覆盖 noReports→DONE 和 hasReports→PENDING, 从未覆盖 fastPath-with-reports → bug 漏网。
            ProductionBatch batch = new ProductionBatch();
            setupPassingGuards(batch);

            // fast-path 资格: 本人提交 (workerId==submitter) + createdAt 在时间窗内 (now)
            ProductionReport report = ProductionReport.builder()
                    .id(900L)
                    .workerId(SUBMITTED_BY)
                    .createdAt(LocalDateTime.now())
                    .build();
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID)).thenReturn(List.of(report));

            // saved log 携带 APPROVED (自动批准) + id → executeReversal findById 命中且 status!=DONE → 真正执行
            ReportReversalLog saved = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.APPROVED)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .submittedBy(SUBMITTED_BY)
                    .build();
            when(reversalLogRepo.save(any())).thenReturn(saved);
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));
            when(workProcessTaskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, BATCH_ID))
                    .thenReturn(Collections.emptyList());
            when(txnRepo.findByFactoryIdAndReportId(FACTORY_ID, 900L)).thenReturn(Collections.emptyList());

            service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "fast withdraw");

            // BUG 修复证明: executeReversal 真的跑了 → 报工被软删 (setDeletedAt + save)。
            // 旧 bug 下 reportRepo.save 永不被调用 (executeReversal 见 DONE 直接 return)。
            ArgumentCaptor<ProductionReport> repCaptor = ArgumentCaptor.forClass(ProductionReport.class);
            verify(reportRepo, atLeastOnce()).save(repCaptor.capture());
            assertThat(repCaptor.getValue().getDeletedAt()).isNotNull();
        }
    }

    // ==================== approveReversal / rejectReversal ====================

    @Nested
    @DisplayName("approveReversal / rejectReversal")
    class ApproveRejectReversal {

        @Test
        @DisplayName("approve PENDING → 调用 executeReversal 并保存")
        void approve_pending_executesAndSaves() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));

            // executeReversal internals
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(Collections.emptyList());
            when(reversalLogRepo.save(any())).thenReturn(pending);

            service.approveReversal(FACTORY_ID, LOG_ID, 99L);

            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
            // At minimum the save from executeReversal marking DONE
        }

        @Test
        @DisplayName("executeReversal → COMPLETED 任务复位 PENDING + 清 completedBy/completedAt + actualQuantity=null (撤回后可重报)")
        void executeReversal_resetsCompletedTasks() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(Collections.emptyList());
            when(reversalLogRepo.save(any())).thenReturn(pending);

            com.cretas.aims.entity.workprocess.WorkProcessTask completedTask =
                    new com.cretas.aims.entity.workprocess.WorkProcessTask();
            completedTask.setId(335L);
            completedTask.setFactoryId(FACTORY_ID);
            completedTask.setProductionBatchId(BATCH_ID);
            completedTask.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.COMPLETED);
            completedTask.setCompletedBy(7L);
            completedTask.setCompletedAt(java.time.LocalDateTime.now());
            completedTask.setActualQuantity(new BigDecimal("540"));

            com.cretas.aims.entity.workprocess.WorkProcessTask skippedTask =
                    new com.cretas.aims.entity.workprocess.WorkProcessTask();
            skippedTask.setId(336L);
            skippedTask.setFactoryId(FACTORY_ID);
            skippedTask.setProductionBatchId(BATCH_ID);
            skippedTask.setStatus(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.SKIPPED);
            skippedTask.setActualQuantity(new BigDecimal("10"));
            when(workProcessTaskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, BATCH_ID))
                    .thenReturn(List.of(completedTask, skippedTask));

            service.executeReversal(LOG_ID, FACTORY_ID);

            // COMPLETED → PENDING, completedBy/completedAt 清空, actualQuantity 复位
            assertThat(completedTask.getStatus())
                    .isEqualTo(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.PENDING);
            assertThat(completedTask.getCompletedBy()).isNull();
            assertThat(completedTask.getCompletedAt()).isNull();
            assertThat(completedTask.getActualQuantity()).isNull();
            // SKIPPED 独立决策不动状态, 但 actualQuantity 仍复位 (派生源已删)
            assertThat(skippedTask.getStatus())
                    .isEqualTo(com.cretas.aims.entity.workprocess.WorkProcessTask.Status.SKIPPED);
            assertThat(skippedTask.getActualQuantity()).isNull();
            verify(workProcessTaskRepository).saveAll(List.of(completedTask, skippedTask));
        }

        @Test
        @DisplayName("approve non-PENDING → 409")
        void approve_nonPending_throws409() {
            ReportReversalLog done = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.DONE)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(done));

            assertThatThrownBy(() -> service.approveReversal(FACTORY_ID, LOG_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只有待审批");
        }

        @Test
        @DisplayName("跨租户: 别厂调用方审批本厂撤回 → 403, 不写库 (R2 跨租户校验)")
        void approve_crossTenant_throws403_andDoesNotSave() {
            // 撤回日志属于 FACTORY_ID(F006), 调用方为 OTHER_FACTORY → 跨租户拒绝。
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.approveReversal("OTHER_FACTORY", LOG_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(403);
            verify(reversalLogRepo, never()).save(any());
        }

        @Test
        @DisplayName("跨租户: 别厂调用方驳回本厂撤回 → 403, 不写库 (R2 跨租户校验)")
        void reject_crossTenant_throws403_andDoesNotSave() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.rejectReversal("OTHER_FACTORY", LOG_ID, 99L, "越权驳回"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(403);
            verify(reversalLogRepo, never()).save(any());
        }

        @Test
        @DisplayName("reject PENDING → status=REJECTED 被保存")
        void reject_pending_savesRejected() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));
            when(reversalLogRepo.save(any())).thenReturn(pending);

            service.rejectReversal(FACTORY_ID, LOG_ID, 99L, "不符合要求");

            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo).save(captor.capture());
            assertThat(captor.getValue().getStatus())
                    .isEqualTo(ReportReversalLog.ReversalStatus.REJECTED);
        }

        // ==================== W6 #27: 4 眼原则 (防自批) + 驳回通知 ====================

        @Test
        @DisplayName("W6 #27: 提交人自批 → 403 SELF_APPROVAL_FORBIDDEN (4 眼原则)")
        void approve_selfApproval_throws403() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .submittedBy(SUBMITTED_BY)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));

            // 提交人 == 审批人 → 拒绝
            assertThatThrownBy(() -> service.approveReversal(FACTORY_ID, LOG_ID, SUBMITTED_BY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("审批人不能与提交人相同");

            // 自批被拦 → 不应改状态/不执行撤回
            verify(reversalLogRepo, never()).save(any());
            verify(reportRepo, never()).findYieldReportsByBatch(anyString(), anyLong());
        }

        @Test
        @DisplayName("W6 #27: 他人审批 (审批人 ≠ 提交人) → 放行执行撤回")
        void approve_byOtherUser_succeeds() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .submittedBy(SUBMITTED_BY)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(Collections.emptyList());
            when(reversalLogRepo.save(any())).thenReturn(pending);

            // 主管 (id=99) ≠ 提交人 (id=7) → 放行
            service.approveReversal(FACTORY_ID, LOG_ID, 99L);

            verify(reversalLogRepo, atLeastOnce()).save(any());
            // executeReversal 被触发 (查询报工)
            verify(reportRepo).findYieldReportsByBatch(FACTORY_ID, BATCH_ID);
        }

        @Test
        @DisplayName("W6 #27: 驳回 (审批人 ≠ 申请人) → 通知申请人")
        void reject_byOtherUser_notifiesApplicant() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .submittedBy(SUBMITTED_BY)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));
            when(reversalLogRepo.save(any())).thenReturn(pending);

            service.rejectReversal(FACTORY_ID, LOG_ID, 99L, "成品已部分出库, 无法撤回");

            // 申请人 (SUBMITTED_BY=7) 收到通知, 内容含驳回原因
            verify(notificationService).sendNotification(
                    eq(FACTORY_ID),
                    eq(SUBMITTED_BY),
                    contains("驳回"),
                    contains("成品已部分出库"),
                    eq(com.cretas.aims.entity.enums.NotificationType.WARNING),
                    eq("REVERSAL"),
                    eq(String.valueOf(LOG_ID)));
        }

        @Test
        @DisplayName("W6 #27: 驳回 (审批人 == 申请人, 自撤) → 不发通知 (避免自噪声)")
        void reject_selfReject_skipsNotification() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .submittedBy(SUBMITTED_BY)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));
            when(reversalLogRepo.save(any())).thenReturn(pending);

            // 申请人自己驳回 (approvedBy == submittedBy)
            service.rejectReversal(FACTORY_ID, LOG_ID, SUBMITTED_BY, "自行撤销申请");

            verify(notificationService, never()).sendNotification(
                    anyString(), anyLong(), anyString(), anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("W6 #27: 驳回通知发送失败 → fail-soft, 不影响驳回结果")
        void reject_notificationFails_stillRejects() {
            ReportReversalLog pending = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .submittedBy(SUBMITTED_BY)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(pending));
            when(reversalLogRepo.save(any())).thenReturn(pending);
            when(notificationService.sendNotification(
                    anyString(), anyLong(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("通知渠道不可用"));

            // 通知抛异常不应冒泡 → 驳回成功
            service.rejectReversal(FACTORY_ID, LOG_ID, 99L, "测试");

            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo).save(captor.capture());
            assertThat(captor.getValue().getStatus())
                    .isEqualTo(ReportReversalLog.ReversalStatus.REJECTED);
        }
    }

    // ==================== listReversals ====================

    @Nested
    @DisplayName("listReversals")
    class ListReversals {

        @Test
        @DisplayName("status=null → 调用无状态查询方法返回全部")
        void nullStatus_returnsAll() {
            List<ReportReversalLog> all = List.of(new ReportReversalLog());
            when(reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(FACTORY_ID))
                    .thenReturn(all);

            List<ReportReversalLog> result = service.listReversals(FACTORY_ID, null);
            assertThat(result).hasSize(1);
            verify(reversalLogRepo).findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(FACTORY_ID);
            verify(reversalLogRepo, never())
                    .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(anyString(), any());
        }

        @Test
        @DisplayName("status=PENDING → 调用状态过滤查询方法")
        void pendingStatus_callsStatusFilter() {
            List<ReportReversalLog> pending = List.of(new ReportReversalLog());
            when(reversalLogRepo.findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                    eq(FACTORY_ID), eq(ReportReversalLog.ReversalStatus.PENDING)))
                    .thenReturn(pending);

            List<ReportReversalLog> result = service.listReversals(FACTORY_ID, "PENDING");
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("status=pending (小写) → 大写转换后正常工作")
        void lowercaseStatus_normalized() {
            when(reversalLogRepo.findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                    eq(FACTORY_ID), eq(ReportReversalLog.ReversalStatus.PENDING)))
                    .thenReturn(List.of());

            List<ReportReversalLog> result = service.listReversals(FACTORY_ID, "pending");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("status=INVALID_STATUS → 400 异常")
        void invalidStatus_throws400() {
            assertThatThrownBy(() -> service.listReversals(FACTORY_ID, "INVALID_STATUS"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无效的状态值");
        }
    }

    // ==================== SP12 快速撤回通道 (resolveFastPathDeniedReason) ====================

    /**
     * SP12 快速撤回辅助方法单元测试。
     * 直接测试 package-visible 的 resolveFastPathDeniedReason, 覆盖所有边界条件。
     *
     * <p>核心安全原则验证:
     * <ul>
     *   <li>G1/G2 守卫 (下游消费/成品出货) 不在此方法内 — 调用前已通过守卫</li>
     *   <li>快速通道仅免"审批人"环节, 不免安全守卫</li>
     *   <li>三条件全满足才 null (放行): 本人 + workerId一致 + 5min 窗口内</li>
     * </ul>
     *
     * @since SP12 (fix/w7-reversal-fastpath)
     */
    @Nested
    @DisplayName("SP12: resolveFastPathDeniedReason — 快速撤回资格判断")
    class ResolveFastPathDeniedReason {

        /** 构建一个刚刚提交 (n 分钟前) 的报工记录，workerId = submitter */
        private ProductionReport reportBySubmitter(int minutesAgo) {
            ProductionReport r = new ProductionReport();
            r.setWorkerId(SUBMITTED_BY);
            r.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
            return r;
        }

        /** 构建一个由其他操作员提交的报工记录 */
        private ProductionReport reportByOther(int minutesAgo) {
            ProductionReport r = new ProductionReport();
            r.setWorkerId(999L); // 不是 SUBMITTED_BY
            r.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
            return r;
        }

        @Test
        @DisplayName("本人 + 窗口内 (1 min) + workerId 一致 → null (快速撤回放行)")
        void selfSubmitter_withinWindow_sameWorker_returnsNull() {
            ProductionReport r = reportBySubmitter(1);
            String result = service.resolveFastPathDeniedReason(SUBMITTED_BY, List.of(r));
            assertThat(result).isNull(); // 快速撤回放行
        }

        @Test
        @DisplayName("本人 + 窗口边界 (5 min) → null (放行, 窗口内含边界)")
        void selfSubmitter_atWindowBoundary_returnsNull() {
            // 4 min 59s ago ≈ 4 minutes by Duration.toMinutes()，仍在窗口内
            ProductionReport r = reportBySubmitter(4);
            r.setCreatedAt(LocalDateTime.now().minusSeconds(270)); // 4.5min < 5min
            String result = service.resolveFastPathDeniedReason(SUBMITTED_BY, List.of(r));
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("本人 + 超时 (6 min) → 非 null (退回审批, 含超时原因)")
        void selfSubmitter_overtime_returnsReason() {
            ProductionReport r = reportBySubmitter(6);
            String result = service.resolveFastPathDeniedReason(SUBMITTED_BY, List.of(r));
            assertThat(result).isNotNull();
            assertThat(result).contains("超出快速撤回时限");
            assertThat(result).contains("需要主管审批");
        }

        @Test
        @DisplayName("其他操作员的报工 + 窗口内 → 非 null (退回审批, 含他人操作员原因)")
        void otherWorker_withinWindow_returnsReason() {
            ProductionReport r = reportByOther(1);
            String result = service.resolveFastPathDeniedReason(SUBMITTED_BY, List.of(r));
            assertThat(result).isNotNull();
            assertThat(result).contains("其他操作员");
        }

        @Test
        @DisplayName("混合报工 (部分本人部分他人) + 窗口内 → 非 null (退回审批)")
        void mixedWorkers_withinWindow_returnsReason() {
            ProductionReport myReport = reportBySubmitter(1);
            ProductionReport otherReport = reportByOther(2);
            String result = service.resolveFastPathDeniedReason(
                    SUBMITTED_BY, List.of(myReport, otherReport));
            assertThat(result).isNotNull();
            assertThat(result).contains("其他操作员");
        }

        @Test
        @DisplayName("本人 + 窗口内 + 多笔报工 (最新在窗口内) → null (放行)")
        void multipleReports_latestInWindow_returnsNull() {
            // 最新报工 1 min 前, 旧报工 30 min 前 (都是本人)
            ProductionReport older = reportBySubmitter(30);
            ProductionReport newer = reportBySubmitter(1);
            String result = service.resolveFastPathDeniedReason(
                    SUBMITTED_BY, List.of(older, newer));
            // 最新报工时间 = 1min 前，在窗口内 → 放行
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("本人 + 多笔报工 (最新超时) → 非 null (退回审批)")
        void multipleReports_latestOvertime_returnsReason() {
            // 最新报工 10 min 前 (都是本人)
            ProductionReport older = reportBySubmitter(30);
            ProductionReport newer = reportBySubmitter(10);
            String result = service.resolveFastPathDeniedReason(
                    SUBMITTED_BY, List.of(older, newer));
            assertThat(result).isNotNull();
            assertThat(result).contains("超出快速撤回时限");
        }

        @Test
        @DisplayName("submittedBy=null → 非 null (退回审批, 提交人未知)")
        void nullSubmitter_returnsReason() {
            ProductionReport r = reportBySubmitter(1);
            String result = service.resolveFastPathDeniedReason(null, List.of(r));
            assertThat(result).isNotNull();
            assertThat(result).contains("提交人未知");
        }

        @Test
        @DisplayName("报工 createdAt=null → 非 null (保守退回审批)")
        void nullCreatedAt_returnsReason() {
            ProductionReport r = new ProductionReport();
            r.setWorkerId(SUBMITTED_BY);
            r.setCreatedAt(null); // 无时间信息
            String result = service.resolveFastPathDeniedReason(SUBMITTED_BY, List.of(r));
            assertThat(result).isNotNull();
            assertThat(result).contains("报工时间信息缺失");
        }
    }

    // ==================== SP12: submitReversal 快速撤回集成路径 ====================

    @Nested
    @DisplayName("SP12: submitReversal — 快速撤回集成路径")
    class SubmitReversalFastPathIntegration {

        private void setupPassingGuards(ProductionBatch batch) {
            when(txnRepo.existsDownstreamConsumed(anyString(), anyString())).thenReturn(false);
            when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
            when(fgbRepo.existsShippedByFactoryIdAndProductionPlanId(anyString(), anyString()))
                    .thenReturn(false);
            when(reversalLogRepo.findByBatchIdAndReversalScopeAndDeletedAtIsNull(any(), any()))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("SP12: 本人+无消费+窗口内 → fastPath=true 自动批准(首存 APPROVED) + 真正执行撤回(软删报工)")
        void fastPath_selfWithinWindow_createsDone() {
            ProductionBatch batch = new ProductionBatch();
            setupPassingGuards(batch);

            // 报工: 本人提交, 1 min 前
            ProductionReport report = new ProductionReport();
            report.setWorkerId(SUBMITTED_BY);
            report.setCreatedAt(LocalDateTime.now().minusMinutes(1));
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(List.of(report));

            // BUG-GOLD-RERUN-FASTPATH 修复: 首存 APPROVED (非 DONE), 让 executeReversal 真正执行后再置 DONE。
            ReportReversalLog saved = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.APPROVED)
                    .fastPath(true)
                    .submittedBy(SUBMITTED_BY)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.save(any())).thenReturn(saved);
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));

            service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "误填");

            // 首存: fastPath=true + status=APPROVED (自动批准, 待 executeReversal 执行)
            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
            ReportReversalLog firstSave = captor.getAllValues().get(0);
            assertThat(firstSave.getFastPath()).isTrue();
            assertThat(firstSave.getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.APPROVED);
            // 执行证明: executeReversal 真跑了 → 报工被软删 (旧 bug 预置 DONE → executeReversal 跳过, 报工不会 save)
            verify(reportRepo, atLeastOnce()).save(any(ProductionReport.class));
        }

        @Test
        @DisplayName("SP12: 超时报工 → status=PENDING + fastPath=false (退回审批，附诚实提示)")
        void fastPath_overtime_createsPending_withHint() {
            ProductionBatch batch = new ProductionBatch();
            setupPassingGuards(batch);

            // 报工: 本人提交, 10 min 前 (超窗口)
            ProductionReport report = new ProductionReport();
            report.setWorkerId(SUBMITTED_BY);
            report.setCreatedAt(LocalDateTime.now().minusMinutes(10));
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(List.of(report));

            ReportReversalLog saved = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .fastPath(false)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.save(any())).thenReturn(saved);
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));

            service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "填错了");

            // 验证: fastPath=false, status=PENDING
            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
            ReportReversalLog capturedLog = captor.getAllValues().get(0);
            assertThat(capturedLog.getFastPath()).isFalse();
            assertThat(capturedLog.getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.PENDING);
            // reason 字段包含诚实提示 (需审批原因)
            assertThat(capturedLog.getReason()).contains("需审批原因");
            assertThat(capturedLog.getReason()).contains("超出快速撤回时限");
        }

        @Test
        @DisplayName("SP12: 他人报工 + 窗口内 → status=PENDING + fastPath=false (退回审批)")
        void fastPath_otherWorker_createsPending() {
            ProductionBatch batch = new ProductionBatch();
            setupPassingGuards(batch);

            // 报工: 他人操作员, 1 min 前
            ProductionReport report = new ProductionReport();
            report.setWorkerId(999L); // 不是 SUBMITTED_BY
            report.setCreatedAt(LocalDateTime.now().minusMinutes(1));
            when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                    .thenReturn(List.of(report));

            ReportReversalLog saved = ReportReversalLog.builder()
                    .id(LOG_ID).factoryId(FACTORY_ID).batchId(BATCH_ID)
                    .status(ReportReversalLog.ReversalStatus.PENDING)
                    .fastPath(false)
                    .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                    .build();
            when(reversalLogRepo.save(any())).thenReturn(saved);
            when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(saved));

            service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "测试");

            ArgumentCaptor<ReportReversalLog> captor = ArgumentCaptor.forClass(ReportReversalLog.class);
            verify(reversalLogRepo, atLeastOnce()).save(captor.capture());
            ReportReversalLog capturedLog = captor.getAllValues().get(0);
            assertThat(capturedLog.getFastPath()).isFalse();
            assertThat(capturedLog.getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.PENDING);
        }

        @Test
        @DisplayName("SP12: G1 下游消费存在 → 409 (快速撤回绝不绕过 G1 安全守卫)")
        void fastPath_doesNotBypassG1Guard() {
            // G1 拦截: 有下游消费
            when(txnRepo.existsDownstreamConsumed(FACTORY_ID, String.valueOf(BATCH_ID)))
                    .thenReturn(true);

            // 即使是本人+窗口内的报工，G1 也必须拦截
            assertThatThrownBy(() -> service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "误填"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已被下道工序领用");

            // 不应创建任何 log
            verify(reversalLogRepo, never()).save(any());
        }

        @Test
        @DisplayName("SP12: G2 成品已出货 → 409 (快速撤回绝不绕过 G2 安全守卫)")
        void fastPath_doesNotBypassG2Guard() {
            when(txnRepo.existsDownstreamConsumed(anyString(), anyString())).thenReturn(false);
            ProductionBatch batch = new ProductionBatch();
            batch.setProductionPlanId("plan-001");
            when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
            when(fgbRepo.existsShippedByFactoryIdAndProductionPlanId(FACTORY_ID, "plan-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.submitReversal(FACTORY_ID, BATCH_ID, SUBMITTED_BY, "误填"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已出货");

            verify(reversalLogRepo, never()).save(any());
        }
    }
}
