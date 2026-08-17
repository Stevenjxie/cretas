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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报工幂等：同一次点击的重试<b>不许再扣一次料</b>。
 *
 * <h2>为什么（生产实测）</h2>
 *
 * <p>2026-08-17 对抗性审计在 F006 上打了两次<b>完全相同</b>的领用报工（间隔 37ms）：
 * 两次都 200、产生两条报工（23804/23805）、库存 {@code consumed 0.00 → 1.00}
 * —— <b>领 0.5 扣了 1.0</b>。已冲销。
 *
 * <p>防护此前<b>只在前端</b>（{@code disabled={submitting}}）：
 * 网络重试、离线重发、进程被杀后重开、API 直调，全都绕得过去。
 * 而报工改成<b>提交即入账</b>之后，重复提交 = 库存立刻被多扣，风险等级变了。
 *
 * <h2>⛔ 为什么不用时间窗去重</h2>
 *
 * <p>legacy 栈用的是 5 分钟窗。<b>不照抄</b>：报工是<b>合法高频</b>动作
 * （分段报工／领两批料／一道工序多人分报），时间窗会把这些<b>正当操作</b>当成重复。
 * <b>一道会误拦正当操作的闸，最后一定会被绕开或关掉。</b>
 *
 * <p>请求号能区分二者 —— 所以本测试里<b>阴性对照和阳性一样重要</b>：
 * 同号必须拦，<b>不同号必须放行</b>。
 */
class ReportIdempotencyTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH = 10759L;

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WipInventoryService wipInventoryService;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        wipInventoryService = mock(WipInventoryService.class);
        WorkProcessRepository processRepo = mock(WorkProcessRepository.class);
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(processRepo.findById(anyString())).thenReturn(Optional.of(new WorkProcess()));
        when(reportRepo.findYieldReportsByBatch(anyString(), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask(anyString(), any())).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(70001L);
            return r;
        });
        when(taskRepo.save(any(WorkProcessTask.class))).thenAnswer(i -> i.getArgument(0));

        WorkProcessTask t = new WorkProcessTask();
        t.setId(1786L); t.setFactoryId(FACTORY); t.setProductionBatchId(BATCH);
        t.setProcessOrder(2); t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        t.setWorkProcessId("WP-LU"); t.setProductTypeId("PT");
        when(taskRepo.findByFactoryIdAndId(FACTORY, 1786L)).thenReturn(Optional.of(t));
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY, BATCH))
                .thenReturn(List.of(t));

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
                wipInventoryService,
                mock(ProductWorkProcessAssigneeRepository.class),
                mock(com.cretas.aims.repository.ProductWorkProcessRepository.class),
                new com.cretas.aims.service.yield.CostReconcileService(),
                mock(ProcessSheetRowRepository.class)
        );
    }

    private YieldReportRequest req(String clientRequestId) {
        YieldReportRequest r = new YieldReportRequest();
        r.setWorkProcessTaskId(1786L);
        r.setClientRequestId(clientRequestId);
        r.setReportKind("OUTPUT");
        r.setOutputQuantity(new BigDecimal("1.5"));
        r.setOutputUnit("kg");
        return r;
    }

    @Test
    @DisplayName("🔴 同号重试: 只产生一条报工, 不许再扣一次料, 返回第一次的 reportId")
    void sameKeyIsReplayedNotReExecuted() {
        ProductionReport first = new ProductionReport();
        first.setId(70001L);
        first.setFactoryId(FACTORY);
        first.setClientRequestId("req-abc");
        when(reportRepo.findFirstByFactoryIdAndClientRequestIdAndDeletedAtIsNull(FACTORY, "req-abc"))
                .thenReturn(Optional.of(first));

        Map<String, Object> out = svc.submitReport(FACTORY, BATCH, 7L, req("req-abc"));

        assertThat(out.get("reportId")).as("应当拿回第一次那条").isEqualTo(70001L);
        assertThat(out.get("idempotentReplay")).isEqualTo(true);
        verify(reportRepo, never()).save(any(ProductionReport.class));
        verify(wipInventoryService, never())
                .postApprovedOutput(any(), any(), any(), any());   // ⚠️ 一律 any(), 不用 anyString()
    }

    @Test
    @DisplayName("⛔ 阴性对照: 【不同号】必须放行 —— 正当的第二笔不许被拦")
    void differentKeyIsNotBlocked() {
        when(reportRepo.findFirstByFactoryIdAndClientRequestIdAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> out = svc.submitReport(FACTORY, BATCH, 7L, req("req-second"));

        assertThat(out.get("idempotentReplay")).as("这是真的第二笔, 不是重试").isNull();
        verify(reportRepo, times(1)).save(any(ProductionReport.class));
        verify(wipInventoryService, times(1)).postApprovedOutput(any(), any(), any(), any());
    }

    @Test
    @DisplayName("⛔ 阴性对照: 不带号(旧版 App) 保持现状, 不查重也不拦")
    void noKeyKeepsLegacyBehaviour() {
        Map<String, Object> out = svc.submitReport(FACTORY, BATCH, 7L, req(null));

        assertThat(out.get("idempotentReplay")).isNull();
        verify(reportRepo, times(1)).save(any(ProductionReport.class));
        verify(reportRepo, never())
                .findFirstByFactoryIdAndClientRequestIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("幂等键必须落库 —— 否则下一次重试查不到, 等于没做")
    void keyIsPersisted() {
        when(reportRepo.findFirstByFactoryIdAndClientRequestIdAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<ProductionReport> cap =
                org.mockito.ArgumentCaptor.forClass(ProductionReport.class);

        svc.submitReport(FACTORY, BATCH, 7L, req("req-persist"));

        verify(reportRepo).save(cap.capture());
        assertThat(cap.getValue().getClientRequestId())
                .as("键没落库 = 下次重试查不到 = 幂等形同虚设")
                .isEqualTo("req-persist");
    }
}
