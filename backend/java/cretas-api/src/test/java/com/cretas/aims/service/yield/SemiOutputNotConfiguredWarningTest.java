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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 未配「半成品产出编号」的工序，报工时必须出声 —— ⛔ 不许静默。
 *
 * <p>设计卡：{@code docs/decisions/2026-08-17-未配半成品产出编号必须出声.md}
 *
 * <p>2026-08-17 F006 走查实测：批次 10759 三道工序<b>一道都没配</b>（全厂 42/186）。
 * 后果链全程静默：{@code output-options} 返回空 → RN 不显示半成品产出栏 →
 * 报工返回 200 → 产出不入账 → <b>下一道领不到料，没有人被告知</b>。
 *
 * <p>⚠️ 判据必须<b>窄</b>：末道产出的是成品，本来就不该进半成品库，对它报警是误报；
 * 一道天天误报的提示会被无视，那时它的覆盖率归零。
 */
class SemiOutputNotConfiguredWarningTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH = 10759L;

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(reportRepo.findYieldReportsByBatch(anyString(), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask(anyString(), any())).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(90001L);
            return r;
        });
        when(taskRepo.save(any(WorkProcessTask.class))).thenAnswer(i -> i.getArgument(0));

        svc = new YieldReportServiceImpl(
                reportRepo, taskRepo, processRepo,
                new YieldCalculationServiceImpl(),
                mock(ProcessingService.class),
                mock(FactorySettingsRepository.class),
                mock(MaterialBatchRepository.class),
                mock(ProductTypeRepository.class),
                mock(ProductionBatchRepository.class),
                mock(ProductionPlanRepository.class),
                wipRepo, mock(BatchLineageEdgeRepository.class),
                new ObjectMapper(),
                mock(ProcessMaterialRecipeRepository.class),
                mock(WipInventoryService.class),
                mock(ProductWorkProcessAssigneeRepository.class),
                mock(com.cretas.aims.repository.ProductWorkProcessRepository.class),
                new com.cretas.aims.service.yield.CostReconcileService(),
                mock(ProcessSheetRowRepository.class)
        );
    }

    private WorkProcessTask task(long id, int order, WorkProcessTask.Status st, String wpId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id); t.setFactoryId(FACTORY); t.setProductionBatchId(BATCH);
        t.setProcessOrder(order); t.setStatus(st); t.setWorkProcessId(wpId);
        t.setProductTypeId("PT_F006_LSM");
        return t;
    }

    private void wire(WorkProcessTask reported, String semiCode, WorkProcessTask... all) {
        when(taskRepo.findByFactoryIdAndId(FACTORY, reported.getId())).thenReturn(Optional.of(reported));
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, BATCH))
                .thenReturn(List.of(all));
        WorkProcess wp = new WorkProcess();
        wp.setSemiFinishedOutputCode(semiCode);
        when(processRepo.findById(reported.getWorkProcessId())).thenReturn(Optional.of(wp));
    }

    private Map<String, Object> report(WorkProcessTask t) {
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(t.getId());
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("1.5"));
        req.setOutputUnit("kg");
        return svc.submitReport(FACTORY, BATCH, 7L, req);
    }

    @Test
    @DisplayName("🔴 中间道未配半成品产出编号 + 后面还有未完成工序 → 必须出声")
    void warnsWhenMiddleProcessHasNoSemiCode() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, null, t2, t3);

        assertThat(report(t2).get("semiOutputNotConfigured"))
                .as("下一道还等着领料, 而本道产出无处可入 —— 必须告诉用户")
                .asString().contains("半成品产出编号").contains("下一道领不到料");
    }

    @Test
    @DisplayName("⛔ 阴性对照: 末道(后面没有工序) 必须安静 —— 成品本来就不进半成品库")
    void silentOnLastProcess() {
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.IN_PROGRESS, "WP-PACK");
        WorkProcessTask t1 = task(1785L, 1, WorkProcessTask.Status.COMPLETED, "WP-CUT");
        wire(t3, null, t1, t3);

        assertThat(report(t3).get("semiOutputNotConfigured"))
                .as("末道对它报警是误报; 天天误报的提示最终会被无视")
                .isNull();
    }

    @Test
    @DisplayName("⛔ 阴性对照: 已配 semiCode 必须安静")
    void silentWhenConfigured() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, "SEMI-LUZHI", t2, t3);

        assertThat(report(t2).get("semiOutputNotConfigured")).isNull();
    }

    @Test
    @DisplayName("⛔ 阴性对照: 下游都已完成/跳过 时安静")
    void silentWhenDownstreamAllDone() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.SKIPPED, "WP-PACK");
        wire(t2, null, t2, t3);

        assertThat(report(t2).get("semiOutputNotConfigured")).isNull();
    }
}
