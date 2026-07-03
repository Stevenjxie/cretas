package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProcessWorkReportSubmitRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProcessTask;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProcessWorkReportingServiceImpl 单元测试
 *
 * 测试覆盖:
 * - 审批流程 (UT-PWR-01~04)
 * - 驳回流程 (UT-PWR-05~06)
 * - 批量审批 (UT-PWR-07~08)
 * - 补报流程 (UT-PWR-09~13)
 * - 冲销流程 (UT-PWR-14~16)
 * - 补报状态恢复 (UT-PWR-17~19)
 * - 数量校准 (UT-PWR-20~21)
 *
 * @author Cretas Team
 * @since 2026-03-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessWorkReportingServiceImpl 报工审批服务测试")
class ProcessWorkReportingServiceImplTest {

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F999";
    private static final String TASK_ID = "PT-001";
    private static final Long REPORT_ID = 100L;
    private static final Long APPROVER_ID = 10L;
    private static final Long WORKER_ID = 20L;

    @Mock
    private ProductionReportRepository reportRepository;

    @Mock
    private ProcessTaskRepository taskRepository;

    @Mock
    private WorkProcessRepository workProcessRepository;

    @Mock
    private ProductTypeRepository productTypeRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private WorkProcessTaskRepository workProcessTaskRepository;

    @Mock
    private WipInventoryService wipInventoryService;

    @InjectMocks
    private ProcessWorkReportingServiceImpl service;

    @Captor
    private ArgumentCaptor<ProductionReport> reportCaptor;

    @Captor
    private ArgumentCaptor<ProcessTask> taskCaptor;

    // ==================== Helper builders ====================

    private ProductionReport.ProductionReportBuilder pendingReport() {
        return ProductionReport.builder()
                .id(REPORT_ID)
                .factoryId(FACTORY_ID)
                .processTaskId(TASK_ID)
                .workerId(WORKER_ID)
                .reporterName("张三")
                .reportType(ProductionReport.ReportType.PROGRESS)
                .outputQuantity(new BigDecimal("50"))
                .processCategory("切割")
                .approvalStatus("PENDING")
                .status(ProductionReport.Status.SUBMITTED)
                .isSupplemental(false);
    }

    private ProcessTask.ProcessTaskBuilder activeTask(ProcessTaskStatus status) {
        return ProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .productionRunId("RUN-001")
                .productTypeId("PROD-001")
                .workProcessId("WP-001")
                .plannedQuantity(new BigDecimal("1000"))
                .completedQuantity(new BigDecimal("200"))
                .pendingQuantity(new BigDecimal("100"))
                .unit("kg")
                .createdBy(1L)
                .status(status);
    }

    private WorkProcessTask.WorkProcessTaskBuilder wipTask() {
        return WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .productWorkProcessId(8001L)
                .workProcessId("WP-001")
                .productTypeId("PROD-001")
                .processOrder(2)
                .status(WorkProcessTask.Status.IN_PROGRESS)
                .plannedQuantity(new BigDecimal("1000"))
                .plannedUnit("kg");
    }

    // ==================== 审批流程 ====================

    @Nested
    @DisplayName("审批报工 approveReport")
    class ApproveReportTests {

        @Test
        @DisplayName("UT-PWR-01: 审批通过 — 设置APPROVED，completedQty增加，pendingQty减少")
        void approveReport_setsApproved_syncsQuantities() {
            ProductionReport report = pendingReport().build();
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS).build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            // For checkAndRestoreFromSupplementing — task is IN_PROGRESS, not SUPPLEMENTING, so early return
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(report);
            when(taskRepository.save(any(ProcessTask.class))).thenReturn(task);

            Map<String, Object> result = service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            assertEquals("APPROVED", result.get("status"));
            assertEquals(REPORT_ID, result.get("reportId"));

            // Verify report saved with APPROVED
            verify(reportRepository).save(reportCaptor.capture());
            ProductionReport savedReport = reportCaptor.getValue();
            assertEquals("APPROVED", savedReport.getApprovalStatus());
            assertEquals(APPROVER_ID, savedReport.getApprovedBy());
            assertNotNull(savedReport.getApprovedAt());

            // Verify task quantities synced: completed 200+50=250, pending 100-50=50
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ProcessTask savedTask = taskCaptor.getValue();
            assertEquals(new BigDecimal("250"), savedTask.getCompletedQuantity());
            assertEquals(new BigDecimal("50"), savedTask.getPendingQuantity());
        }

        @Test
        @DisplayName("UT-PWR-01B: 审批通过且绑定WorkProcessTask时才写入WIP权威账")
        void approveReport_withWipBridge_postsWipAfterApproval() {
            ProductionReport report = pendingReport()
                    .workProcessTaskId(7001L)
                    .batchId(9001L)
                    .inputQuantity(new BigDecimal("35"))
                    .inputUnit("kg")
                    .sourceWipNo("WIP-S1")
                    .outputUnit("kg")
                    .build();
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS).build();
            WorkProcessTask workProcessTask = wipTask().build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(report);
            when(taskRepository.save(any(ProcessTask.class))).thenReturn(task);
            when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, 7001L))
                    .thenReturn(Optional.of(workProcessTask));

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            verify(wipInventoryService).postApprovedOutput(FACTORY_ID, report, workProcessTask, APPROVER_ID);
        }

        @Test
        @DisplayName("UT-PWR-01C: RN Yield 报工只有 WorkProcessTask 且带审批入账标记时, Web 审批后写入 WIP")
        void approveReport_rnYieldApprovalMarker_postsWipWithoutProcessTask() {
            ProductionReport report = ProductionReport.builder()
                    .id(REPORT_ID)
                    .factoryId(FACTORY_ID)
                    .processTaskId(null)
                    .workProcessTaskId(7001L)
                    .batchId(9001L)
                    .reportType("YIELD")
                    .reportKind("INPUT")
                    .inputQuantity(new BigDecimal("35"))
                    .inputUnit("kg")
                    .sourceWipNo("WIP-S1")
                    .approvalStatus("PENDING")
                    .status(ProductionReport.Status.SUBMITTED)
                    .customFields(Map.of("reportStack", "YIELD", "wipPostingMode", "APPROVAL"))
                    .build();
            WorkProcessTask workProcessTask = wipTask().build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(report);
            when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, 7001L))
                    .thenReturn(Optional.of(workProcessTask));

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            verify(taskRepository, never()).findById(anyString());
            verify(wipInventoryService).postApprovedOutput(FACTORY_ID, report, workProcessTask, APPROVER_ID);
        }

        @Test
        @DisplayName("UT-PWR-01D: 历史 RN Yield 报工无审批入账标记时跳过 WIP, 防止重复过账")
        void approveReport_legacyRnYieldWithoutMarker_skipsWipPosting() {
            ProductionReport report = ProductionReport.builder()
                    .id(REPORT_ID)
                    .factoryId(FACTORY_ID)
                    .processTaskId(null)
                    .workProcessTaskId(7001L)
                    .batchId(9001L)
                    .reportType("YIELD")
                    .outputQuantity(new BigDecimal("50"))
                    .approvalStatus("PENDING")
                    .status(ProductionReport.Status.SUBMITTED)
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(report);

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            verify(workProcessTaskRepository, never()).findByFactoryIdAndId(anyString(), anyLong());
            verify(wipInventoryService, never()).postApprovedOutput(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("UT-PWR-02: 工厂ID不匹配 — 抛出BusinessException")
        void approveReport_wrongFactory_throwsException() {
            ProductionReport report = pendingReport().build();
            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.approveReport(OTHER_FACTORY, REPORT_ID, APPROVER_ID));

            assertTrue(ex.getMessage().contains("不属于当前工厂"));
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-PWR-03: 已审批的报工再次审批 — 抛出409")
        void approveReport_alreadyApproved_throws409() {
            ProductionReport report = pendingReport().approvalStatus("APPROVED").build();
            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("已被处理"));
        }

        @Test
        @DisplayName("UT-PWR-04: 审批首条报工 — PENDING任务自动转IN_PROGRESS")
        void approveReport_pendingTask_autoTransitionsToInProgress() {
            ProductionReport report = pendingReport().build();
            ProcessTask task = activeTask(ProcessTaskStatus.PENDING).build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any())).thenReturn(report);
            when(taskRepository.save(any())).thenReturn(task);

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            // The last save should have IN_PROGRESS status
            ProcessTask savedTask = taskCaptor.getAllValues().stream()
                    .filter(t -> t.getStatus() == ProcessTaskStatus.IN_PROGRESS)
                    .findFirst()
                    .orElse(null);
            assertNotNull(savedTask, "任务应从PENDING自动转为IN_PROGRESS");
            assertEquals(ProcessTaskStatus.IN_PROGRESS, savedTask.getStatus());
        }
    }

    // ==================== 驳回流程 ====================

    @Nested
    @DisplayName("驳回报工 rejectReport")
    class RejectReportTests {

        @Test
        @DisplayName("UT-PWR-05: 驳回 — 设置REJECTED，pendingQty减少并clamp到0")
        void rejectReport_setsRejected_pendingQtyDecreasesClampedToZero() {
            // pendingQuantity=30, outputQuantity=50 → 30-50 should clamp to 0
            ProductionReport report = pendingReport().outputQuantity(new BigDecimal("50")).build();
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .pendingQuantity(new BigDecimal("30"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any())).thenReturn(report);
            when(taskRepository.save(any())).thenReturn(task);

            Map<String, Object> result = service.rejectReport(FACTORY_ID, REPORT_ID, "质量不合格", APPROVER_ID);

            assertEquals("REJECTED", result.get("status"));

            verify(reportRepository).save(reportCaptor.capture());
            assertEquals("REJECTED", reportCaptor.getValue().getApprovalStatus());
            assertEquals("质量不合格", reportCaptor.getValue().getRejectedReason());

            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            // 30 - 50 = -20, clamped to ZERO
            assertEquals(0, taskCaptor.getValue().getPendingQuantity().compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("UT-PWR-06: 已驳回的报工再次驳回 — 抛出409")
        void rejectReport_alreadyRejected_throws409() {
            ProductionReport report = pendingReport().approvalStatus("REJECTED").build();
            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.rejectReport(FACTORY_ID, REPORT_ID, "重复驳回", APPROVER_ID));

            assertEquals(409, ex.getCode());
        }
    }

    // ==================== 批量审批 ====================

    @Nested
    @DisplayName("批量审批 batchApprove")
    class BatchApproveTests {

        @Test
        @DisplayName("UT-PWR-07: 批量审批N条 — 全部APPROVED，各任务数量同步")
        void batchApprove_approvesAll_syncsQuantities() {
            Long reportId2 = 101L;
            String taskId2 = "PT-002";

            ProductionReport report1 = pendingReport().build();
            ProductionReport report2 = pendingReport().id(reportId2).processTaskId(taskId2)
                    .outputQuantity(new BigDecimal("30")).build();

            ProcessTask task1 = activeTask(ProcessTaskStatus.IN_PROGRESS).build();
            ProcessTask task2 = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .id(taskId2)
                    .completedQuantity(new BigDecimal("100"))
                    .pendingQuantity(new BigDecimal("60"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report1));
            when(reportRepository.findById(reportId2)).thenReturn(Optional.of(report2));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task1));
            when(taskRepository.findById(taskId2)).thenReturn(Optional.of(task2));
            when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // For checkAndRestoreFromSupplementing (tasks are IN_PROGRESS, not SUPPLEMENTING)

            Map<String, Object> result = service.batchApprove(FACTORY_ID, List.of(REPORT_ID, reportId2), APPROVER_ID);

            assertEquals(2, result.get("approved"));

            // Both reports should be saved as APPROVED
            verify(reportRepository, times(2)).save(reportCaptor.capture());
            List<ProductionReport> savedReports = reportCaptor.getAllValues();
            assertTrue(savedReports.stream().allMatch(r -> "APPROVED".equals(r.getApprovalStatus())));
        }

        @Test
        @DisplayName("UT-PWR-08: 批量审批中工厂ID不匹配 — 部分批准 + 跳过原因")
        void batchApprove_wrongFactory_skipsAndReports() {
            // 2026-05-09: 行为变化 (R69-BUG-1 fix) — wrong factory 现在被静默
            // 跳过 (skippedDetails 含 reason=WRONG_FACTORY)，而不是抛异常。
            // partial success 走 200 通道 + UI 可读 skippedIds.
            ProductionReport report1 = pendingReport().build();
            ProductionReport report2 = pendingReport().id(101L).factoryId(OTHER_FACTORY).build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report1));
            when(reportRepository.findById(101L)).thenReturn(Optional.of(report2));
            when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(
                    activeTask(ProcessTaskStatus.IN_PROGRESS).build()));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> result = service.batchApprove(
                    FACTORY_ID, List.of(REPORT_ID, 101L), APPROVER_ID);

            // 第 1 条 approved, 第 2 条 (wrong factory) skipped with reason
            assertEquals(1, result.get("approved"));
            assertEquals(1, result.get("skipped"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skippedIds =
                    (List<Map<String, Object>>) result.get("skippedIds");
            assertEquals(1, skippedIds.size());
            assertEquals(101L, skippedIds.get(0).get("reportId"));
            assertEquals("WRONG_FACTORY", skippedIds.get(0).get("reason"));
        }
    }

    // ==================== 补报流程 ====================

    @Nested
    @DisplayName("补报 submitSupplement")
    class SubmitSupplementTests {

        @Test
        @DisplayName("UT-PWR-09: COMPLETED任务补报 — 进入SUPPLEMENTING，保存previousTerminalStatus")
        void submitSupplement_completedTask_entersSupplementing() {
            ProcessTask task = activeTask(ProcessTaskStatus.COMPLETED)
                    .pendingQuantity(BigDecimal.ZERO).build();
            ProductionReport savedReport = pendingReport().id(200L).isSupplemental(true).build();

            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any())).thenReturn(savedReport);
            when(taskRepository.save(any())).thenReturn(task);

            Map<String, Object> result = service.submitSupplement(
                    FACTORY_ID, TASK_ID, WORKER_ID, "张三", new BigDecimal("25"), "切割", null);

            assertEquals("SUPPLEMENTING", result.get("taskStatus"));

            // Task should have been saved with SUPPLEMENTING status and previous=COMPLETED
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            List<ProcessTask> taskSaves = taskCaptor.getAllValues();
            // First save: transition to SUPPLEMENTING
            ProcessTask firstSave = taskSaves.get(0);
            assertEquals(ProcessTaskStatus.SUPPLEMENTING, firstSave.getStatus());
            assertEquals("COMPLETED", firstSave.getPreviousTerminalStatus());
        }

        @Test
        @DisplayName("UT-PWR-10: CLOSED任务补报 — 进入SUPPLEMENTING")
        void submitSupplement_closedTask_entersSupplementing() {
            ProcessTask task = activeTask(ProcessTaskStatus.CLOSED)
                    .pendingQuantity(BigDecimal.ZERO).build();
            ProductionReport savedReport = pendingReport().id(201L).isSupplemental(true).build();

            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any())).thenReturn(savedReport);
            when(taskRepository.save(any())).thenReturn(task);

            service.submitSupplement(FACTORY_ID, TASK_ID, WORKER_ID, "张三", new BigDecimal("10"), "包装", null);

            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ProcessTask firstSave = taskCaptor.getAllValues().get(0);
            assertEquals(ProcessTaskStatus.SUPPLEMENTING, firstSave.getStatus());
            assertEquals("CLOSED", firstSave.getPreviousTerminalStatus());
        }

        @Test
        @DisplayName("UT-PWR-11: 已在SUPPLEMENTING的任务再次补报 — 不重复设置状态")
        void submitSupplement_alreadySupplementing_doesNotReenterState() {
            ProcessTask task = activeTask(ProcessTaskStatus.SUPPLEMENTING)
                    .previousTerminalStatus("COMPLETED")
                    .pendingQuantity(new BigDecimal("25"))
                    .build();
            ProductionReport savedReport = pendingReport().id(202L).isSupplemental(true).build();

            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any())).thenReturn(savedReport);
            when(taskRepository.save(any())).thenReturn(task);

            service.submitSupplement(FACTORY_ID, TASK_ID, WORKER_ID, "李四", new BigDecimal("15"), "切割", null);

            // Task save should only happen for pending quantity update, not for status transition
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            List<ProcessTask> saves = taskCaptor.getAllValues();
            // All saves should keep previousTerminalStatus as COMPLETED (not overwritten)
            for (ProcessTask save : saves) {
                assertEquals("COMPLETED", save.getPreviousTerminalStatus());
            }
            // Pending should increase: 25 + 15 = 40
            ProcessTask lastSave = saves.get(saves.size() - 1);
            assertEquals(new BigDecimal("40"), lastSave.getPendingQuantity());
        }

        @Test
        @DisplayName("UT-PWR-12: IN_PROGRESS任务补报 — 抛出异常「只有已完成或已关闭的任务可以补报」")
        void submitSupplement_inProgress_throwsException() {
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS).build();
            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.submitSupplement(
                            FACTORY_ID, TASK_ID, WORKER_ID, "张三", new BigDecimal("10"), "切割", null));

            assertTrue(ex.getMessage().contains("只有已完成或已关闭的任务可以补报"));
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-PWR-13: 补报创建的报工 — isSupplemental=true, approvalStatus=PENDING, pendingQty增加")
        void submitSupplement_createsSupplementalReport() {
            ProcessTask task = activeTask(ProcessTaskStatus.COMPLETED)
                    .pendingQuantity(BigDecimal.ZERO).build();
            ProductionReport savedReport = pendingReport().id(203L).isSupplemental(true).build();

            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.save(any())).thenReturn(savedReport);
            when(taskRepository.save(any())).thenReturn(task);

            service.submitSupplement(FACTORY_ID, TASK_ID, WORKER_ID, "张三", new BigDecimal("30"), "切割", null);

            verify(reportRepository).save(reportCaptor.capture());
            ProductionReport createdReport = reportCaptor.getValue();
            assertTrue(createdReport.getIsSupplemental(), "报工应标记为补报");
            assertEquals("PENDING", createdReport.getApprovalStatus());
            assertEquals(TASK_ID, createdReport.getProcessTaskId());
            assertEquals(new BigDecimal("30"), createdReport.getOutputQuantity());
            assertEquals(ProductionReport.Status.SUBMITTED, createdReport.getStatus());

            // Pending quantity on task: 0 + 30 = 30
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ProcessTask lastTaskSave = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);
            assertEquals(new BigDecimal("30"), lastTaskSave.getPendingQuantity());
        }
    }

    // ==================== 冲销流程 ====================

    @Nested
    @DisplayName("冲销 createReversal")
    class CreateReversalTests {

        @Test
        @DisplayName("UT-PWR-14: 冲销已审批报工 — 创建负数记录，completedQty减少")
        void createReversal_createsNegativeRecord_decreasesCompletedQty() {
            ProductionReport original = pendingReport()
                    .approvalStatus("APPROVED")
                    .outputQuantity(new BigDecimal("80"))
                    .productName("牛肉干")
                    .build();
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .completedQuantity(new BigDecimal("300"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(original));
            when(reportRepository.save(any())).thenAnswer(inv -> {
                ProductionReport r = inv.getArgument(0);
                r.setId(300L);
                return r;
            });
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(taskRepository.save(any())).thenReturn(task);

            Map<String, Object> result = service.createReversal(FACTORY_ID, REPORT_ID, APPROVER_ID, "数据录入错误");

            assertEquals(REPORT_ID, result.get("originalId"));

            // Reversal report has negative quantity
            verify(reportRepository).save(reportCaptor.capture());
            ProductionReport reversal = reportCaptor.getValue();
            assertEquals(new BigDecimal("-80"), reversal.getOutputQuantity());
            assertEquals("APPROVED", reversal.getApprovalStatus());
            assertEquals(REPORT_ID, reversal.getReversalOfId());
            assertFalse(reversal.getIsSupplemental());

            // Task completed: 300 - 80 = 220
            verify(taskRepository).save(taskCaptor.capture());
            assertEquals(new BigDecimal("220"), taskCaptor.getValue().getCompletedQuantity());
        }

        @Test
        @DisplayName("UT-PWR-15: 冲销非APPROVED报工 — 抛出异常")
        void createReversal_notApproved_throwsException() {
            ProductionReport report = pendingReport().approvalStatus("PENDING").build();
            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createReversal(FACTORY_ID, REPORT_ID, APPROVER_ID, "错误"));

            assertTrue(ex.getMessage().contains("只能冲销已审批通过的报工"));
            verify(reportRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-PWR-16: 冲销后completedQty不足 — clamp到ZERO")
        void createReversal_completedQtyClampedToZero() {
            ProductionReport original = pendingReport()
                    .approvalStatus("APPROVED")
                    .outputQuantity(new BigDecimal("500"))
                    .build();
            // Task only has 200 completed, but reversing 500
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .completedQuantity(new BigDecimal("200"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(original));
            when(reportRepository.save(any())).thenAnswer(inv -> {
                ProductionReport r = inv.getArgument(0);
                r.setId(301L);
                return r;
            });
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(taskRepository.save(any())).thenReturn(task);

            service.createReversal(FACTORY_ID, REPORT_ID, APPROVER_ID, "全量冲销");

            verify(taskRepository).save(taskCaptor.capture());
            // 200 - 500 = -300, clamped to ZERO
            assertEquals(0, taskCaptor.getValue().getCompletedQuantity().compareTo(BigDecimal.ZERO));
        }
    }

    // ==================== 补报状态恢复 ====================

    @Nested
    @DisplayName("checkAndRestoreFromSupplementing 补报状态恢复（通过审批/驳回间接测试）")
    class RestoreFromSupplementingTests {

        @Test
        @DisplayName("UT-PWR-17: 审批最后一条补报 — 任务从SUPPLEMENTING恢复为COMPLETED")
        void approveLastSupplement_restoresToCompleted() {
            ProductionReport supplementReport = pendingReport()
                    .isSupplemental(true)
                    .outputQuantity(new BigDecimal("20"))
                    .build();

            ProcessTask task = activeTask(ProcessTaskStatus.SUPPLEMENTING)
                    .previousTerminalStatus("COMPLETED")
                    .pendingQuantity(new BigDecimal("20"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(supplementReport));
            // syncQuantitiesToTask needs findById
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            // checkAndRestoreFromSupplementing: no more pending supplementals
            when(reportRepository.findByProcessTaskIdAndApprovalStatusAndDeletedAtIsNull(TASK_ID, "PENDING"))
                    .thenReturn(Collections.emptyList());
            when(reportRepository.save(any())).thenReturn(supplementReport);
            when(taskRepository.save(any())).thenReturn(task);

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            verify(taskRepository, atLeast(2)).save(taskCaptor.capture());
            List<ProcessTask> allSaves = taskCaptor.getAllValues();
            // The last save from checkAndRestoreFromSupplementing should restore to COMPLETED
            ProcessTask lastSave = allSaves.get(allSaves.size() - 1);
            assertEquals(ProcessTaskStatus.COMPLETED, lastSave.getStatus());
            assertNull(lastSave.getPreviousTerminalStatus());
        }

        @Test
        @DisplayName("UT-PWR-18: 审批最后一条补报 — 任务从SUPPLEMENTING恢复为CLOSED")
        void approveLastSupplement_restoresToClosed() {
            ProductionReport supplementReport = pendingReport()
                    .isSupplemental(true)
                    .outputQuantity(new BigDecimal("10"))
                    .build();

            ProcessTask task = activeTask(ProcessTaskStatus.SUPPLEMENTING)
                    .previousTerminalStatus("CLOSED")
                    .pendingQuantity(new BigDecimal("10"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(supplementReport));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.findByProcessTaskIdAndApprovalStatusAndDeletedAtIsNull(TASK_ID, "PENDING"))
                    .thenReturn(Collections.emptyList());
            when(reportRepository.save(any())).thenReturn(supplementReport);
            when(taskRepository.save(any())).thenReturn(task);

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            verify(taskRepository, atLeast(2)).save(taskCaptor.capture());
            List<ProcessTask> allSaves = taskCaptor.getAllValues();
            ProcessTask lastSave = allSaves.get(allSaves.size() - 1);
            assertEquals(ProcessTaskStatus.CLOSED, lastSave.getStatus());
            assertNull(lastSave.getPreviousTerminalStatus());
        }

        @Test
        @DisplayName("UT-PWR-19: 审批一条补报但仍有其他待审补报 — 保持SUPPLEMENTING")
        void approveOneSupplement_othersPending_staysSupplementing() {
            ProductionReport report1 = pendingReport()
                    .isSupplemental(true)
                    .outputQuantity(new BigDecimal("20"))
                    .build();

            // Another pending supplemental report still exists
            ProductionReport report2 = pendingReport()
                    .id(102L)
                    .isSupplemental(true)
                    .approvalStatus("PENDING")
                    .build();

            ProcessTask task = activeTask(ProcessTaskStatus.SUPPLEMENTING)
                    .previousTerminalStatus("COMPLETED")
                    .pendingQuantity(new BigDecimal("40"))
                    .build();

            when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report1));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            when(reportRepository.findByProcessTaskIdAndApprovalStatusAndDeletedAtIsNull(TASK_ID, "PENDING"))
                    .thenReturn(List.of(report2));
            when(reportRepository.save(any())).thenReturn(report1);
            when(taskRepository.save(any())).thenReturn(task);

            service.approveReport(FACTORY_ID, REPORT_ID, APPROVER_ID);

            // Task should remain SUPPLEMENTING (checkAndRestore finds pending supplements)
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            List<ProcessTask> allSaves = taskCaptor.getAllValues();
            // None of the saves should have overwritten SUPPLEMENTING to something else
            // The saves from syncQuantitiesToTask keep SUPPLEMENTING (it's not PENDING, so no auto-transition)
            for (ProcessTask save : allSaves) {
                assertEquals(ProcessTaskStatus.SUPPLEMENTING, save.getStatus(),
                        "任务应保持SUPPLEMENTING，因为还有待审补报");
            }
        }
    }

    // ==================== 正常报工 — SQL-side dedup (#566 T4-B6) ====================

    @Nested
    @DisplayName("正常报工 submitNormalReport — SQL-side dedup")
    class SubmitNormalReportDedupTests {

        @Test
        @DisplayName("UT-PWR-22: 30s 内重复提交 — findRecentDuplicate 命中, 返回 duplicate=true, 不保存新报工")
        void submitNormalReport_recentDuplicate_returnsExistingWithoutSaving() {
            BigDecimal qty = new BigDecimal("50");
            ProductionReport existing = pendingReport().outputQuantity(qty).build();

            when(reportRepository.findRecentDuplicate(eq(TASK_ID), eq(WORKER_ID), eq(qty), any()))
                    .thenReturn(Optional.of(existing));

            Map<String, Object> result = service.submitNormalReport(
                    FACTORY_ID, TASK_ID, WORKER_ID, "张三", qty, "测试备注");

            assertEquals(Boolean.TRUE, result.get("duplicate"));
            assertEquals(REPORT_ID, result.get("reportId"));
            assertEquals(qty, result.get("pendingQuantity"));

            // 关键: 命中后应 short-circuit, 既不查 task 也不保存任何东西
            verify(taskRepository, never()).findByFactoryIdAndId(anyString(), anyString());
            verify(reportRepository, never()).save(any(ProductionReport.class));
            verify(taskRepository, never()).save(any(ProcessTask.class));

            // 关键: 不再调用 in-memory 全表 stream 方法
            verify(reportRepository, never())
                    .findByProcessTaskIdAndDeletedAtIsNull(anyString());
        }

        @Test
        @DisplayName("UT-PWR-23: 无重复 — findRecentDuplicate 返 empty, 正常创建报工")
        void submitNormalReport_noDuplicate_createsReport() {
            BigDecimal qty = new BigDecimal("75");
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .pendingQuantity(new BigDecimal("100"))
                    .build();
            ProductionReport saved = pendingReport().id(500L).outputQuantity(qty).build();

            when(reportRepository.findRecentDuplicate(eq(TASK_ID), eq(WORKER_ID), eq(qty), any()))
                    .thenReturn(Optional.empty());
            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                    .thenReturn(Optional.of(task));
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(saved);
            when(taskRepository.save(any(ProcessTask.class))).thenReturn(task);

            Map<String, Object> result = service.submitNormalReport(
                    FACTORY_ID, TASK_ID, WORKER_ID, "张三", qty, "正常报工");

            assertNull(result.get("duplicate"), "无重复时不应有 duplicate 标志");
            assertEquals(500L, result.get("reportId"));
            assertEquals("IN_PROGRESS", result.get("taskStatus"));

            // 报工应保存为 PENDING
            verify(reportRepository).save(reportCaptor.capture());
            ProductionReport createdReport = reportCaptor.getValue();
            assertEquals("PENDING", createdReport.getApprovalStatus());
            assertEquals(WORKER_ID, createdReport.getWorkerId());
            assertEquals(qty, createdReport.getOutputQuantity());
            assertFalse(createdReport.getIsSupplemental());

            // task.pendingQuantity 应增加 75 → 175
            verify(taskRepository).save(taskCaptor.capture());
            assertEquals(new BigDecimal("175"), taskCaptor.getValue().getPendingQuantity());

            // 关键: in-memory 全表方法不再被调用
            verify(reportRepository, never())
                    .findByProcessTaskIdAndDeletedAtIsNull(anyString());
        }

        @Test
        @DisplayName("UT-PWR-23B: 正常报工绑定WorkProcessTask时保存桥接字段并只做WIP来源校验")
        void submitNormalReport_withWipBridge_persistsBridgeAndValidatesSourceWip() {
            BigDecimal qty = new BigDecimal("75");
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .pendingQuantity(new BigDecimal("100"))
                    .build();
            WorkProcessTask workProcessTask = wipTask().build();
            ProductionReport saved = pendingReport().id(502L).outputQuantity(qty).build();
            ProcessWorkReportSubmitRequest request = ProcessWorkReportSubmitRequest.builder()
                    .processTaskId(TASK_ID)
                    .reporterName("张三")
                    .outputQuantity(qty)
                    .inputQuantity(new BigDecimal("35"))
                    .inputUnit("kg")
                    .outputUnit("kg")
                    .sourceWipNo("WIP-S1")
                    .workProcessTaskId(7001L)
                    .batchId(9001L)
                    .build();

            when(reportRepository.findRecentDuplicate(eq(TASK_ID), eq(WORKER_ID), eq(qty), any()))
                    .thenReturn(Optional.empty());
            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                    .thenReturn(Optional.of(task));
            when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, 7001L))
                    .thenReturn(Optional.of(workProcessTask));
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(saved);
            when(taskRepository.save(any(ProcessTask.class))).thenReturn(task);

            service.submitNormalReport(FACTORY_ID, WORKER_ID, request);

            verify(wipInventoryService).validateSourceWip(FACTORY_ID, "WIP-S1", new BigDecimal("35"), "kg", null);
            verify(wipInventoryService, never()).postApprovedOutput(anyString(), any(), any(), any());
            verify(reportRepository).save(reportCaptor.capture());
            ProductionReport createdReport = reportCaptor.getValue();
            assertEquals(9001L, createdReport.getBatchId());
            assertEquals(7001L, createdReport.getWorkProcessTaskId());
            assertEquals(2, createdReport.getProcessOrder());
            assertEquals("PROD-001", createdReport.getProductTypeId());
            assertEquals(new BigDecimal("35"), createdReport.getInputQuantity());
            assertEquals("kg", createdReport.getInputUnit());
            assertEquals("kg", createdReport.getOutputUnit());
            assertEquals("WIP-S1", createdReport.getSourceWipNo());
        }

        @Test
        @DisplayName("UT-PWR-24: PENDING task 首次报工 — auto-transition 到 IN_PROGRESS")
        void submitNormalReport_pendingTask_autoTransitionsToInProgress() {
            BigDecimal qty = new BigDecimal("40");
            ProcessTask task = activeTask(ProcessTaskStatus.PENDING)
                    .pendingQuantity(BigDecimal.ZERO)
                    .build();
            ProductionReport saved = pendingReport().id(501L).outputQuantity(qty).build();

            when(reportRepository.findRecentDuplicate(eq(TASK_ID), eq(WORKER_ID), eq(qty), any()))
                    .thenReturn(Optional.empty());
            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                    .thenReturn(Optional.of(task));
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(saved);
            when(taskRepository.save(any(ProcessTask.class))).thenReturn(task);

            Map<String, Object> result = service.submitNormalReport(
                    FACTORY_ID, TASK_ID, WORKER_ID, "张三", qty, null);

            assertEquals("IN_PROGRESS", result.get("taskStatus"));

            verify(taskRepository).save(taskCaptor.capture());
            assertEquals(ProcessTaskStatus.IN_PROGRESS, taskCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("UT-PWR-25: COMPLETED task — 拒绝正常报工 (409)")
        void submitNormalReport_completedTask_throws409() {
            BigDecimal qty = new BigDecimal("10");
            ProcessTask task = activeTask(ProcessTaskStatus.COMPLETED).build();

            when(reportRepository.findRecentDuplicate(eq(TASK_ID), eq(WORKER_ID), eq(qty), any()))
                    .thenReturn(Optional.empty());
            when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                    .thenReturn(Optional.of(task));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.submitNormalReport(
                            FACTORY_ID, TASK_ID, WORKER_ID, "张三", qty, null));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("正常报工仅限进行中或待开始的任务"));
            verify(reportRepository, never()).save(any(ProductionReport.class));
        }
    }

    // ==================== 数量校准 ====================

    @Nested
    @DisplayName("数量校准 calibrateTaskQuantities")
    class CalibrateTests {

        @Test
        @DisplayName("UT-PWR-20: 检测到漂移 — 校正completedQty和pendingQty")
        void calibrate_driftDetected_correctsQuantities() {
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .completedQuantity(new BigDecimal("200"))    // current (drifted)
                    .pendingQuantity(new BigDecimal("100"))       // current (drifted)
                    .build();

            when(taskRepository.findActiveTasksForCalibration(FACTORY_ID))
                    .thenReturn(List.of(task));
            when(reportRepository.sumApprovedQuantityByTaskId(TASK_ID))
                    .thenReturn(Map.of("total", new BigDecimal("250")));   // actual approved
            when(reportRepository.sumPendingQuantityByTaskId(TASK_ID))
                    .thenReturn(Map.of("total", new BigDecimal("80")));    // actual pending
            when(taskRepository.save(any())).thenReturn(task);

            service.calibrateTaskQuantities(FACTORY_ID);

            verify(taskRepository).save(taskCaptor.capture());
            ProcessTask calibrated = taskCaptor.getValue();
            assertEquals(new BigDecimal("250"), calibrated.getCompletedQuantity());
            assertEquals(new BigDecimal("80"), calibrated.getPendingQuantity());
        }

        @Test
        @DisplayName("UT-PWR-21: 无漂移 — 不触发save")
        void calibrate_noDrift_noSave() {
            ProcessTask task = activeTask(ProcessTaskStatus.IN_PROGRESS)
                    .completedQuantity(new BigDecimal("200"))
                    .pendingQuantity(new BigDecimal("100"))
                    .build();

            when(taskRepository.findActiveTasksForCalibration(FACTORY_ID))
                    .thenReturn(List.of(task));
            when(reportRepository.sumApprovedQuantityByTaskId(TASK_ID))
                    .thenReturn(Map.of("total", new BigDecimal("200")));   // matches
            when(reportRepository.sumPendingQuantityByTaskId(TASK_ID))
                    .thenReturn(Map.of("total", new BigDecimal("100")));   // matches

            service.calibrateTaskQuantities(FACTORY_ID);

            verify(taskRepository, never()).save(any());
        }
    }

    // ==================== 待审批列表 productName/processCategory 回落解析 ====================
    // Fool-proof Rule 2 (审批必带身份信息) fix, 2026-07-04. real-data audit against F006 prod
    // (1789 条 PENDING 报工) 发现绝大多数行的 product_name / process_category 列本身是 null,
    // 但报工行自带的 product_type_id / work_process_task_id 可回落解析出真实名称
    // (product_type_id 非空 1602/1789, work_process_task_id 非空 1602/1789, 而旧的
    // process_task_id 路径几乎不用: 仅 1/1789 非空)。

    @Nested
    @DisplayName("待审批列表 — productName/processCategory 回落解析")
    class GetPendingApprovalsTests {

        @Test
        @DisplayName("UT-PWR-22: product_name/process_category 为空但带 product_type_id/work_process_task_id → 回落解析出真实名称")
        void getPendingApprovals_fallsBackToDirectFields_whenNameColumnsBlank() {
            ProductionReport report = ProductionReport.builder()
                    .id(REPORT_ID)
                    .factoryId(FACTORY_ID)
                    .processTaskId(null)
                    .productTypeId("PROD-001")
                    .workProcessTaskId(7001L)
                    .workerId(WORKER_ID)
                    .reporterName("张三")
                    .reportType(ProductionReport.ReportType.PROGRESS)
                    .outputQuantity(new BigDecimal("50"))
                    .approvalStatus("PENDING")
                    .status(ProductionReport.Status.SUBMITTED)
                    .isSupplemental(false)
                    .build();

            Pageable pageable = PageRequest.of(0, 20);
            Page<ProductionReport> page = new PageImpl<>(List.of(report), pageable, 1);
            when(reportRepository.findPendingApprovalsForFactory(FACTORY_ID, "PENDING", pageable))
                    .thenReturn(page);
            when(attachmentRepository.findByFactoryIdAndEntityTypeAndEntityIdInOrderByUploadedAtAsc(
                    anyString(), any(), anyList())).thenReturn(List.of());

            ProductType productType = new ProductType();
            productType.setId("PROD-001");
            productType.setFactoryId(FACTORY_ID);
            productType.setName("叮咚好食光椒麻掌中宝 120g");
            when(productTypeRepository.findByIdAndFactoryId("PROD-001", FACTORY_ID))
                    .thenReturn(Optional.of(productType));

            WorkProcessTask wpt = wipTask().build();
            when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, 7001L))
                    .thenReturn(Optional.of(wpt));

            WorkProcess workProcess = WorkProcess.builder()
                    .id("WP-001")
                    .factoryId(FACTORY_ID)
                    .processName("水解化冻")
                    .build();
            when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, "WP-001"))
                    .thenReturn(Optional.of(workProcess));

            PageResponse<Map<String, Object>> result = service.getPendingApprovals(FACTORY_ID, pageable);

            assertEquals(1, result.getContent().size());
            Map<String, Object> row = result.getContent().get(0);
            assertEquals("叮咚好食光椒麻掌中宝 120g", row.get("productName"));
            assertEquals("水解化冻", row.get("processCategory"));
        }

        @Test
        @DisplayName("UT-PWR-23: 报工行已带 productName/processCategory → 不触发回落查询")
        void getPendingApprovals_usesExistingColumns_noFallbackQuery() {
            ProductionReport report = pendingReport()
                    .productName("已有产品名")
                    .build(); // pendingReport() 已设 processCategory="切割"

            Pageable pageable = PageRequest.of(0, 20);
            Page<ProductionReport> page = new PageImpl<>(List.of(report), pageable, 1);
            when(reportRepository.findPendingApprovalsForFactory(FACTORY_ID, "PENDING", pageable))
                    .thenReturn(page);
            when(attachmentRepository.findByFactoryIdAndEntityTypeAndEntityIdInOrderByUploadedAtAsc(
                    anyString(), any(), anyList())).thenReturn(List.of());

            PageResponse<Map<String, Object>> result = service.getPendingApprovals(FACTORY_ID, pageable);

            Map<String, Object> row = result.getContent().get(0);
            assertEquals("已有产品名", row.get("productName"));
            assertEquals("切割", row.get("processCategory"));
            verifyNoInteractions(productTypeRepository, workProcessTaskRepository);
            verify(taskRepository, never()).findByFactoryIdAndId(anyString(), anyString());
        }

        @Test
        @DisplayName("UT-PWR-24: 全部字段都无法解析(数据缺口) → honest-null, 不伪造名称")
        void getPendingApprovals_returnsNull_whenNoResolvablePath() {
            ProductionReport report = ProductionReport.builder()
                    .id(REPORT_ID)
                    .factoryId(FACTORY_ID)
                    .processTaskId(null)
                    .productTypeId(null)
                    .workProcessTaskId(null)
                    .workerId(WORKER_ID)
                    .reporterName("张三")
                    .reportType(ProductionReport.ReportType.PROGRESS)
                    .outputQuantity(new BigDecimal("50"))
                    .approvalStatus("PENDING")
                    .status(ProductionReport.Status.SUBMITTED)
                    .isSupplemental(false)
                    .build();

            Pageable pageable = PageRequest.of(0, 20);
            Page<ProductionReport> page = new PageImpl<>(List.of(report), pageable, 1);
            when(reportRepository.findPendingApprovalsForFactory(FACTORY_ID, "PENDING", pageable))
                    .thenReturn(page);
            when(attachmentRepository.findByFactoryIdAndEntityTypeAndEntityIdInOrderByUploadedAtAsc(
                    anyString(), any(), anyList())).thenReturn(List.of());

            PageResponse<Map<String, Object>> result = service.getPendingApprovals(FACTORY_ID, pageable);

            Map<String, Object> row = result.getContent().get(0);
            assertNull(row.get("productName"));
            assertNull(row.get("processCategory"));
        }
    }
}
