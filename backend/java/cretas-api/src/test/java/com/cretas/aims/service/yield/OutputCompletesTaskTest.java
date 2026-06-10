package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BUG-2(中) 报工完成不联动任务状态 — 单元测试。
 *
 * <p>OUTPUT 阶段报工提交成功后，WorkProcessTask 必须置 COMPLETED + completedBy = workerId。
 * INPUT / SEGMENT 阶段不得改变 task 状态。
 * Legacy (null reportKind) 整合报工也应置 COMPLETED（向后兼容）。
 */
class OutputCompletesTaskTest {

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private SemiFinishedInventoryRepository wipRepo;
    private BatchLineageEdgeRepository lineageEdgeRepo;
    private ProcessMaterialRecipeRepository recipeRepo;
    private WipInventoryService wipInventoryService;
    private ProductWorkProcessAssigneeRepository pwpAssigneeRepository;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        wipRepo = mock(SemiFinishedInventoryRepository.class);
        lineageEdgeRepo = mock(BatchLineageEdgeRepository.class);
        recipeRepo = mock(ProcessMaterialRecipeRepository.class);
        wipInventoryService = mock(WipInventoryService.class);
        pwpAssigneeRepository = mock(ProductWorkProcessAssigneeRepository.class);

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(recipeRepo.findActiveByFactoryIdAndWorkProcessId(anyString(), anyString())).thenReturn(List.of());
        when(pwpAssigneeRepository.findByProductWorkProcessId(any())).thenReturn(List.of());

        svc = new YieldReportServiceImpl(
                reportRepo, taskRepo, processRepo,
                new YieldCalculationServiceImpl(),
                mock(ProcessingService.class),
                mock(FactorySettingsRepository.class),
                mock(MaterialBatchRepository.class),
                mock(ProductTypeRepository.class),
                mock(ProductionBatchRepository.class),
                mock(ProductionPlanRepository.class),
                wipRepo, lineageEdgeRepo,
                new ObjectMapper(),
                recipeRepo, wipInventoryService, pwpAssigneeRepository
        );
    }

    private WorkProcessTask pendingTask(long id) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId("F006");
        t.setProductionBatchId(99L);
        t.setProcessOrder(1);
        t.setWorkProcessId("WP-ROLLUP");
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        t.setProductTypeId("PT-ZS");
        return t;
    }

    private void stubTask(WorkProcessTask t) {
        when(taskRepo.findByFactoryIdAndId("F006", t.getId())).thenReturn(Optional.of(t));
        when(processRepo.findById(t.getWorkProcessId())).thenReturn(Optional.of(new WorkProcess()));
        when(reportRepo.findYieldReportsByBatch(anyString(), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", t.getId())).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(1000L);
            return r;
        });
        when(taskRepo.save(any(WorkProcessTask.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("OUTPUT 阶段 → task 置 COMPLETED + completedBy = workerId")
    void outputPhase_completesTask() {
        WorkProcessTask t = pendingTask(1L);
        stubTask(t);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(1L);
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("120"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 99L, 7L, req);

        ArgumentCaptor<WorkProcessTask> cap = ArgumentCaptor.forClass(WorkProcessTask.class);
        verify(taskRepo, atLeastOnce()).save(cap.capture());
        // 最后一次 save 应带 COMPLETED 状态和 completedBy
        WorkProcessTask saved = cap.getAllValues().stream()
                .filter(wt -> WorkProcessTask.Status.COMPLETED.equals(wt.getStatus()))
                .findFirst()
                .orElse(null);
        assertThat(saved).as("OUTPUT 报工后 task 应被置为 COMPLETED").isNotNull();
        assertThat(saved.getCompletedBy()).as("completedBy 应为 workerId=7").isEqualTo(7L);
    }

    @Test
    @DisplayName("INPUT 阶段 → task 状态不变 (不能提前 COMPLETED)")
    void inputPhase_doesNotCompleteTask() {
        WorkProcessTask t = pendingTask(2L);
        stubTask(t);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(2L);
        req.setReportKind("INPUT");
        req.setInputQuantity(new BigDecimal("200"));
        req.setInputUnit("kg");

        svc.submitReport("F006", 99L, 7L, req);

        ArgumentCaptor<WorkProcessTask> cap = ArgumentCaptor.forClass(WorkProcessTask.class);
        verify(taskRepo, atLeastOnce()).save(cap.capture());
        // 不应出现 COMPLETED 的 save
        boolean anyCompleted = cap.getAllValues().stream()
                .anyMatch(wt -> WorkProcessTask.Status.COMPLETED.equals(wt.getStatus()));
        assertThat(anyCompleted).as("INPUT 阶段不应置 COMPLETED").isFalse();
    }

    @Test
    @DisplayName("SEGMENT 阶段 → task 状态不变 (不能提前 COMPLETED)")
    void segmentPhase_doesNotCompleteTask() {
        WorkProcessTask t = pendingTask(3L);
        stubTask(t);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(3L);
        req.setReportKind("SEGMENT");
        req.setWorkerCount(5);
        req.setWorkMinutes(60);

        svc.submitReport("F006", 99L, 7L, req);

        ArgumentCaptor<WorkProcessTask> cap = ArgumentCaptor.forClass(WorkProcessTask.class);
        verify(taskRepo, atLeastOnce()).save(cap.capture());
        boolean anyCompleted = cap.getAllValues().stream()
                .anyMatch(wt -> WorkProcessTask.Status.COMPLETED.equals(wt.getStatus()));
        assertThat(anyCompleted).as("SEGMENT 阶段不应置 COMPLETED").isFalse();
    }

    @Test
    @DisplayName("Legacy (null reportKind) 整合报工 → task 置 COMPLETED (向后兼容)")
    void legacyReport_completesTask() {
        WorkProcessTask t = pendingTask(4L);
        stubTask(t);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(4L);
        // reportKind = null (legacy 整合报工)
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("85"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 99L, 12L, req);

        ArgumentCaptor<WorkProcessTask> cap = ArgumentCaptor.forClass(WorkProcessTask.class);
        verify(taskRepo, atLeastOnce()).save(cap.capture());
        WorkProcessTask saved = cap.getAllValues().stream()
                .filter(wt -> WorkProcessTask.Status.COMPLETED.equals(wt.getStatus()))
                .findFirst()
                .orElse(null);
        assertThat(saved).as("Legacy 整合报工后 task 应被置为 COMPLETED").isNotNull();
        assertThat(saved.getCompletedBy()).as("completedBy 应为 workerId=12").isEqualTo(12L);
    }
}
