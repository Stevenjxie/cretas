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
 *
 * <h2>🔴 2026-08-17 当晚订正：这些断言曾经全绿，而线上是 100% 误报</h2>
 *
 * <p>第一版判据问的是「工序配没配 {@code semiFinishedOutputCode}」，
 * 而它宣称的是「产出进没进半成品库」。{@code outputKind} 为 null 的 legacy 路径
 * （F006 的全部数据）在没配编号时会自己派生一个编号照样入库 ——
 * 于是界面弹「下一道领不到料」的同一时刻，下一道 {@code wipAvailable = 2.5 kg}。
 *
 * <p><b>这些测试当时为什么没红</b>：夹具把
 * {@code wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull} 桩成空表，
 * 且 {@code WipInventoryService} 是 mock（{@code postApprovedOutput} 什么都不做）——
 * 也就是<b>喂了一个真实上游永远不会给出的形状</b>：产出永不入库。
 * ⇒ 下面新增 {@code silentWhenLegacyPathAlreadyPostedWip} 复现线上那个形状。
 */
class SemiOutputNotConfiguredWarningTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH = 10759L;

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private SemiFinishedInventoryRepository wipRepo;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        wipRepo = mock(SemiFinishedInventoryRepository.class);

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
    @DisplayName("🔴 中间道产出没进半成品库 + 后面还有未完成工序 → 必须出声")
    void warnsWhenMiddleProcessOutputDidNotLand() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, null, t2, t3);

        assertThat(report(t2).get("semiOutputNotConfigured"))
                .as("下一道还等着领料, 而本道产出无处可入 —— 必须告诉用户")
                .asString().contains("没有进入半成品库").contains("下一道领不到料");
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

    /** 造一条「产出已经落进半成品库」的行，挂在 sourceWorkProcessTaskId 上 —— 线上就是这个形状。 */
    private SemiFinishedInventory landedWip(long sourceTaskId, String no, String qty) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setFactoryId(FACTORY);
        w.setBatchId(BATCH);
        w.setSourceWorkProcessTaskId(sourceTaskId);
        w.setIntermediateBatchNo(no);
        w.setProducedQuantity(new BigDecimal(qty));
        return w;
    }

    @Test
    @DisplayName("🔴 阴性对照: 没配 semiCode 但 legacy 路径已派生编号入库 → 必须安静(线上那次 100% 误报的形状)")
    void silentWhenLegacyPathAlreadyPostedWip() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, null, t2, t3);
        // 派生编号形如 {productTypeId}-B{batchId}-S{order}-{taskId}, 与线上 335 那行同形
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(List.of(landedWip(1786L, "PT_F006_LSM-B10759-S2-1786", "2.5")));

        assertThat(report(t2).get("semiOutputNotConfigured"))
                .as("下一道已经领得到了, 还说「领不到料」是假话; 且它叫人「重新报工」= 同一批产出报两遍")
                .isNull();
    }

    @Test
    @DisplayName("⛔ 别人那道的 WIP 不算数 —— 只认挂在本道上的")
    void warnsWhenWipBelongsToAnotherTask() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, null, t2, t3);
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(List.of(landedWip(1785L, "PT_F006_LSM-B10759-S1-1785", "6")));

        assertThat(report(t2).get("semiOutputNotConfigured"))
                .as("同批次别的工序有 WIP, 不代表【本道】的产出进去了")
                .asString().contains("下一道领不到料");
    }

    @Test
    @DisplayName("⛔ 产出量为 0 的 WIP 行不算「进去了」")
    void warnsWhenWipRowHasZeroProduced() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, null, t2, t3);
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(List.of(landedWip(1786L, "PT_F006_LSM-B10759-S2-1786", "0")));

        assertThat(report(t2).get("semiOutputNotConfigured")).asString().contains("下一道领不到料");
    }

    @Test
    @DisplayName("⛔ 告警文案里不许出现「重新报工」—— 照做就是把同一批产出报两遍")
    void warningMustNotTellUserToReReport() {
        WorkProcessTask t2 = task(1786L, 2, WorkProcessTask.Status.IN_PROGRESS, "WP-LU");
        WorkProcessTask t3 = task(1787L, 3, WorkProcessTask.Status.PENDING, "WP-PACK");
        wire(t2, null, t2, t3);

        assertThat(report(t2).get("semiOutputNotConfigured"))
                .asString()
                .doesNotContain("重新报工")
                .contains("不要重复报工");
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
