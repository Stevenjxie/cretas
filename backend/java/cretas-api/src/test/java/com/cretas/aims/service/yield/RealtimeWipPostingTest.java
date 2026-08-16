package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报工提交即过账 —— 产出不再等审批。
 *
 * <h3>为什么需要这一条</h3>
 *
 * <p>2026-08-16 在 F006 受控走查实测：报工这条链对 WIP 是<b>不对称</b>的 ——
 * 领用在提交时<b>即时扣</b>，产出却要等 Web 审批才由 {@code postApprovedOutput} 过账。
 * 后果是：第②道报完工，料已经扣了、产出还没进库，<b>第③道永远领不到</b>。
 * 客户把这个现象描述成「上工序不报工，下工序就没有库存」，
 * 而实测表明<b>就算他不漏报也一样领不到</b>。
 *
 * <p>修法不是新建过账逻辑：{@code postApprovedOutput} 本身已经是完整事务
 * （消耗源 WIP + 产出入 SFI），并且读 {@code customFields.wipPosted} 幂等。
 * 只需把<b>调用点</b>从审批挪到提交。
 *
 * <p>设计依据：{@code docs/superpowers/specs/2026-08-16-工序报工实时入库-design.md} §四。
 */
class RealtimeWipPostingTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 99L;
    private static final Long WORKER_ID = 7L;

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private SemiFinishedInventoryRepository wipRepo;
    private WipInventoryService wipInventoryService;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        wipRepo = mock(SemiFinishedInventoryRepository.class);
        wipInventoryService = mock(WipInventoryService.class);
        BatchLineageEdgeRepository lineageEdgeRepo = mock(BatchLineageEdgeRepository.class);
        ProcessMaterialRecipeRepository recipeRepo = mock(ProcessMaterialRecipeRepository.class);
        ProductWorkProcessAssigneeRepository pwpAssigneeRepository =
                mock(ProductWorkProcessAssigneeRepository.class);

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
                recipeRepo, wipInventoryService, pwpAssigneeRepository,
                mock(com.cretas.aims.repository.ProductWorkProcessRepository.class),
                new com.cretas.aims.service.yield.CostReconcileService(),
                mock(ProcessSheetRowRepository.class)
        );
    }

    private WorkProcessTask taskAt(long id, int order) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId(FACTORY);
        t.setProductionBatchId(BATCH_ID);
        t.setProcessOrder(order);
        t.setWorkProcessId("WP-LUZHI");
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        t.setProductTypeId("PT-ZS");
        return t;
    }

    private void stub(WorkProcessTask t) {
        when(taskRepo.findByFactoryIdAndId(FACTORY, t.getId())).thenReturn(Optional.of(t));
        when(processRepo.findById(t.getWorkProcessId())).thenReturn(Optional.of(new WorkProcess()));
        when(reportRepo.findYieldReportsByBatch(anyString(), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask(FACTORY, t.getId())).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(23797L);
            return r;
        });
        when(taskRepo.save(any(WorkProcessTask.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("🔴 报工提交后, 产出立即过账进半成品库 —— 不需要任何审批")
    void submitPostsOutputImmediately() {
        WorkProcessTask t2 = taskAt(1786L, 2);
        stub(t2);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(t2.getId());
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("4.50"));
        req.setOutputUnit("kg");

        svc.submitReport(FACTORY, BATCH_ID, WORKER_ID, req);

        // ⚠️ 一律 any(...)：实参为 null 时 anyString() 不匹配, 阴阳两条路都会成立
        //    (本仓记过这个坑, 见 memory feedback_anystring_does_not_match_null...)。
        verify(wipInventoryService, times(1))
                .postApprovedOutput(eq(FACTORY), any(ProductionReport.class),
                        any(WorkProcessTask.class), eq(WORKER_ID));
    }

    @Test
    @DisplayName("阴性对照: INPUT 阶段(只投料不产出)不重复过账")
    void inputPhaseDoesNotPostTwice() {
        WorkProcessTask t2 = taskAt(1787L, 2);
        stub(t2);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(t2.getId());
        req.setReportKind("INPUT");
        req.setInputQuantity(new BigDecimal("6.00"));
        req.setInputUnit("kg");

        svc.submitReport(FACTORY, BATCH_ID, WORKER_ID, req);

        // 过账最多一次 —— postApprovedOutput 内部按 outputKind 分支, 不会因阶段不同而多调
        verify(wipInventoryService, times(1))
                .postApprovedOutput(any(), any(), any(), any());
    }

    @Test
    @DisplayName("⛔ 任务查不到时不得过账 (不替用户凭空造库存)")
    void noPostingWhenTaskMissing() {
        when(taskRepo.findByFactoryIdAndId(eq(FACTORY), any())).thenReturn(Optional.empty());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(999999L);
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("1.00"));

        try {
            svc.submitReport(FACTORY, BATCH_ID, WORKER_ID, req);
        } catch (RuntimeException expected) {
            // 任务不存在时抛业务异常是既有行为, 这里只关心「没有过账」
        }

        verify(wipInventoryService, never()).postApprovedOutput(any(), any(), any(), any());
    }
}
