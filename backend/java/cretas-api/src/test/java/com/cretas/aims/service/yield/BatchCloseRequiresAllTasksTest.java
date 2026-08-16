package com.cretas.aims.service.yield;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次关单必须要求<b>所有工序都完成</b>，⛔ 不按数量猜。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>2026-08-16 在 F006 受控走查实测：legacy 报工栈按
 * {@code actualQuantity >= plannedQuantity} 自动关单，而 {@code planned_quantity}
 * 在 prod 是 {@code NOT NULL}、存货生产把「没有计划数量」存成 <b>0</b>
 * ⇒ 比较恒真，<b>第一次报工就把整个批次关掉</b>，而批次下面还挂着两道 {@code PENDING} 工序，
 * 之后报工一律 409「批次已完成, 不可报工」。
 *
 * <p>yield 栈这边<b>本来就是对的</b>：{@code settleDay} 的关单被
 * {@code batchCloseReadiness} 挡着，逐个工序检查。
 * <b>但在本测试之前没有任何断言钉住它</b> —— 一个「顺手把 SKIPPED 也算成未完成」
 * 或「拿掉 readiness 判断」的改动不会红。
 *
 * <p>⚠️ 本测试<b>不新写</b>一份「批次能不能关」的判定：仓里已经有
 * {@code batchCloseReadiness}，再写一份就是同一件事的第二份实现（本仓反复踩的形态 D）。
 * 这里只是把现有实现钉住。
 *
 * <p>设计：{@code docs/superpowers/specs/2026-08-16-工序报工实时入库-design.md} §三。
 */
class BatchCloseRequiresAllTasksTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 10759L;
    private static final Long WORKER_ID = 7L;

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private ProductionBatchRepository batchRepo;
    private ProcessingService processingService;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        batchRepo = mock(ProductionBatchRepository.class);
        processingService = mock(ProcessingService.class);
        WorkProcessRepository processRepo = mock(WorkProcessRepository.class);
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(processRepo.findById(anyString())).thenReturn(Optional.of(new WorkProcess()));
        when(reportRepo.findUnsettledYieldReports(anyString(), any(), any())).thenReturn(new ArrayList<>());
        when(reportRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ProductionBatch pb = new ProductionBatch();
        pb.setId(BATCH_ID);
        pb.setFactoryId(FACTORY);
        pb.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(pb));
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(i -> i.getArgument(0));

        svc = new YieldReportServiceImpl(
                reportRepo, taskRepo, processRepo,
                new YieldCalculationServiceImpl(),
                processingService,
                mock(FactorySettingsRepository.class),
                mock(MaterialBatchRepository.class),
                mock(ProductTypeRepository.class),
                batchRepo,
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

    private WorkProcessTask task(long id, int order, WorkProcessTask.Status status) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId(FACTORY);
        t.setProductionBatchId(BATCH_ID);
        t.setProcessOrder(order);
        t.setProductTypeId("PT_F006_LSM");
        t.setWorkProcessId("WP-" + order);
        t.setStatus(status);
        return t;
    }

    /**
     * 喂一条完整的三道链（每道都有投入 + 产出）。
     *
     * <p>⚠️ 两个条件缺一不可，否则 {@code settleDay} 根本进不了关单分支，
     * 测试会「因为别的原因」通过：
     * <ul>
     *   <li>{@code batchYield.lastStepOutput > 0} —— 否则外层 if 就不成立；</li>
     *   <li>{@code batchYield.complete} —— 它要求<b>每一道</b>都
     *       {@code totalInput > 0 且 totalOutput > 0}（见 {@code YieldCalculationServiceImpl}）。
     *       只喂末道产出会让它是 false，于是「所有工序完成也不关单」——
     *       那是<b>夹具不够</b>，不是产品有缺陷。</li>
     * </ul>
     */
    private void seedFullYieldChain() {
        List<ProductionReport> reports = new ArrayList<>();
        long[] taskIds = {1785L, 1786L, 1787L};
        String[][] qty = {{"10.0", "6.0"}, {"6.0", "4.5"}, {"4.5", "120"}};
        String[] units = {"kg", "kg", "盒"};
        for (int i = 0; i < taskIds.length; i++) {
            ProductionReport r = new ProductionReport();
            r.setId((long) (i + 1));
            r.setFactoryId(FACTORY);
            r.setBatchId(BATCH_ID);
            r.setWorkProcessTaskId(taskIds[i]);
            r.setProcessOrder(i + 1);
            r.setInputQuantity(new BigDecimal(qty[i][0]));
            r.setInputUnit(i == 0 ? "kg" : units[i - 1]);
            r.setOutputQuantity(new BigDecimal(qty[i][1]));
            r.setOutputUnit(units[i]);
            reports.add(r);
        }
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH_ID)).thenReturn(reports);
    }

    private void seedTasks(WorkProcessTask... tasks) {
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, BATCH_ID))
                .thenReturn(List.of(tasks));
    }

    @Test
    @DisplayName("🔴 还有工序没做完时, 不许关单 —— 即使末道已经有产出")
    void doesNotCloseWhileAnyTaskIncomplete() {
        seedFullYieldChain();
        seedTasks(
                task(1785L, 1, WorkProcessTask.Status.COMPLETED),
                task(1786L, 2, WorkProcessTask.Status.COMPLETED),
                task(1787L, 3, WorkProcessTask.Status.PENDING));   // ← 第③道还没做

        Map<String, Object> out = svc.settleDay(FACTORY, BATCH_ID, WORKER_ID, LocalDate.now(), true);

        assertThat(out.get("completed")).as("还有工序未完成, 不许关单").isEqualTo(false);
        assertThat(out.get("incompleteTaskCount")).isEqualTo(1);
        verify(processingService, never())
                .completeProduction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("✅ 阳性对照: 所有工序完成后才关单（否则「永远不关单」也能通过上一条）")
    void closesOnlyWhenAllTasksDone() {
        seedFullYieldChain();
        seedTasks(
                task(1785L, 1, WorkProcessTask.Status.COMPLETED),
                task(1786L, 2, WorkProcessTask.Status.COMPLETED),
                task(1787L, 3, WorkProcessTask.Status.COMPLETED));

        Map<String, Object> out = svc.settleDay(FACTORY, BATCH_ID, WORKER_ID, LocalDate.now(), true);

        assertThat(out.get("completed")).as("全部工序完成, 应当关单").isEqualTo(true);
        verify(processingService, times(1))
                .completeProduction(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("⛔ SKIPPED / CANCELLED 视为已完成 —— 否则跳过的工序会把批次永远挂住")
    void skippedAndCancelledCountAsDone() {
        seedFullYieldChain();
        seedTasks(
                task(1785L, 1, WorkProcessTask.Status.COMPLETED),
                task(1786L, 2, WorkProcessTask.Status.SKIPPED),
                task(1787L, 3, WorkProcessTask.Status.CANCELLED));

        Map<String, Object> out = svc.settleDay(FACTORY, BATCH_ID, WORKER_ID, LocalDate.now(), true);

        assertThat(out.get("completed"))
                .as("跳过/取消的工序不该把批次永远挂住 (spec 开放项 O1 的答案)")
                .isEqualTo(true);
    }
}
