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

            service.approveReversal(LOG_ID, 99L);

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

            assertThatThrownBy(() -> service.approveReversal(LOG_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只有待审批");
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

            service.rejectReversal(LOG_ID, 99L, "不符合要求");

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
            assertThatThrownBy(() -> service.approveReversal(LOG_ID, SUBMITTED_BY))
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
            service.approveReversal(LOG_ID, 99L);

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

            service.rejectReversal(LOG_ID, 99L, "成品已部分出库, 无法撤回");

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
            service.rejectReversal(LOG_ID, SUBMITTED_BY, "自行撤销申请");

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
            service.rejectReversal(LOG_ID, 99L, "测试");

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
}
