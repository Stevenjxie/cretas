package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class YieldReportServiceImplTest {

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private ProcessingService processingService;
    private YieldCalculationService calcSvc;
    private FactorySettingsRepository factorySettingsRepo;
    private ObjectMapper objectMapper;
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        processingService = mock(ProcessingService.class);
        calcSvc = new YieldCalculationServiceImpl();
        factorySettingsRepo = mock(FactorySettingsRepository.class);
        objectMapper = new ObjectMapper();
        svc = new YieldReportServiceImpl(reportRepo, taskRepo, processRepo, calcSvc, processingService,
                factorySettingsRepo, objectMapper);
    }

    private WorkProcessTask task(long id, int order, String wpId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id); t.setFactoryId("F006"); t.setProductionBatchId(1L);
        t.setProcessOrder(order); t.setWorkProcessId(wpId);
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        return t;
    }

    @Test
    void submitReport_dualWritesActualQuantityToTask() {
        WorkProcessTask t = task(10L, 2, "WP-LU");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-LU")).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });
        // existingTaskReports 在 save 之前查: 已有 1 条产出 80, 本次 output 70 -> task.actualQuantity 应=80+70=150
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("80")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("200"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("70"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        ArgumentCaptor<WorkProcessTask> cap = ArgumentCaptor.forClass(WorkProcessTask.class);
        verify(taskRepo).save(cap.capture());
        assertThat(cap.getValue().getActualQuantity()).isEqualByComparingTo("150");
    }

    @Test
    void submitReport_secondReportForSameTask_leavesBatchNoNull() {
        // 工序序=2 (非首道), 已有 1 条 YIELD 报工 -> 本次是第 2 条 -> intermediateBatchNo 必须 null
        WorkProcessTask t = task(10L, 2, "WP-LU");
        t.setProductTypeId("PT-100");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-LU")).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        ArgumentCaptor<ProductionReport> reportCap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(reportCap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });
        // 已有 1 条报工 -> 本次是第 2 条
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("80")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("70"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        verify(reportRepo).save(any(ProductionReport.class));
        assertThat(reportCap.getValue().getIntermediateBatchNo()).isNull();  // 第 2 条不生成批次号
    }

    @Test
    void submitReport_firstReportForSameTask_generatesBatchNo() {
        // 工序序=2, existingTaskReports 空 -> 本次是首条 -> intermediateBatchNo 非 null
        WorkProcessTask t = task(10L, 2, "WP-LU");
        t.setProductTypeId("PT-100");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-LU")).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        ArgumentCaptor<ProductionReport> reportCap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(reportCap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of());  // 首条

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("70"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        verify(reportRepo).save(any(ProductionReport.class));
        // {产品码}-B{批次}-S{工序序}-{taskId}
        assertThat(reportCap.getValue().getIntermediateBatchNo()).isEqualTo("PT-100-B1-S2-10");
    }

    @Test
    void submitReport_yieldBelowMin_returnsSoftAlert_butStillSaves() {
        WorkProcessTask t = task(10L, 1, "WP-LU");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-LU").factoryId("F006")
                .standardYieldMin(new BigDecimal("0.8000"))
                .standardYieldMax(new BigDecimal("1.5000")).build();
        when(processRepo.findById("WP-LU")).thenReturn(Optional.of(wp));
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });
        when(reportRepo.findYieldReportsByTask(anyString(), eq(10L))).thenReturn(List.of());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("50"));  // yield 0.5 < min 0.8
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("alert")).isEqualTo("BELOW_MIN");
        verify(reportRepo).save(any(ProductionReport.class));  // 软告警仍写入
    }

    // ── A4 超收软告警测试 ──────────────────────────────────────────────────────────

    /** 基础设施: 为超收测试提供通用 mock setup (task 10, WP-01, syMax=1.0, 无历史报工). */
    private void setupOverReceiptBase(WorkProcess wp) {
        WorkProcessTask t = task(10L, 1, "WP-01");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-01")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null); // default 30%
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });
    }

    @Test
    void submitReport_overLimit_noForce_throwsOverReceipt() {
        // input=100, syMax=1.0, target=100, maxAllowed=130, alreadyReported=100, this=31 → cumul=131 > 130
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("1.0000")).build();
        setupOverReceiptBase(wp);
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("100")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("31"));
        req.setOutputUnit("kg");
        // forceSubmit not set → false

        assertThatThrownBy(() -> svc.submitReport("F006", 1L, 5L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("OVER_RECEIPT");
                    assertThat(be.getActionHint()).contains("已报").contains("目标").contains("最多可报");
                });
        verify(reportRepo, never()).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_overLimit_force_saves() {
        // same scenario + forceSubmit=true → saves normally
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("1.0000")).build();
        setupOverReceiptBase(wp);
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("100")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("31"));
        req.setOutputUnit("kg");
        req.setForceSubmit(true);

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("reportId")).isEqualTo(99L);
        verify(reportRepo).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_withinLimit_saves() {
        // input=100, syMax=0.85, target=85, maxAllowed=110.5, alreadyReported=0, this=80 → cumul=80 ≤ 110.5 → no throw
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.8500")).build();
        setupOverReceiptBase(wp);
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("80"));
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);
        assertThat(out.get("reportId")).isEqualTo(99L);
        verify(reportRepo).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_waterRetention_noFalseAlarm() {
        // input=100, syMax=1.35, target=135, maxAllowed=175.5, alreadyReported=130, this=5 → cumul=135 ≤ 175.5 → no throw
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("1.3500")).build();
        setupOverReceiptBase(wp);
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("130")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("5"));
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);
        assertThat(out.get("reportId")).isEqualTo(99L);
        verify(reportRepo).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_nullStandardYieldMax_skips() {
        // standardYieldMax=null → 跳过超收检查
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(null).build();
        setupOverReceiptBase(wp);
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("999")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("999"));
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);
        assertThat(out.get("reportId")).isEqualTo(99L);
        verify(reportRepo).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_nullInput_skips() {
        // inputQuantity=null → 跳过超收检查
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("1.0000")).build();
        setupOverReceiptBase(wp);
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(null);  // null → skip check
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("9999"));
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);
        assertThat(out.get("reportId")).isEqualTo(99L);
        verify(reportRepo).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_customTolerance_usedInsteadOfDefault() throws Exception {
        // yieldOverReceiptTolerance=0.10 → maxAllowed=target×1.10=110, cumul=100+11=111 > 110 → OVER_RECEIPT
        WorkProcess wp = WorkProcess.builder().id("WP-01").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("1.0000")).build();
        WorkProcessTask t = task(10L, 1, "WP-01");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-01")).thenReturn(Optional.of(wp));
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });

        // set custom tolerance = 0.10 (10%) via projection query
        String psJson = "{\"yieldOverReceiptTolerance\":0.10}";
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(psJson);

        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("100")).build()
        ));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("11"));  // cumul=100+11=111 > maxAllowed=110
        req.setOutputUnit("kg");

        assertThatThrownBy(() -> svc.submitReport("F006", 1L, 5L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("OVER_RECEIPT");
                });
        verify(reportRepo, never()).save(any(ProductionReport.class));
    }

    // ── A4 getLimits 预检端点测试 ─────────────────────────────────────────────────

    @Test
    void getLimits_withData_calculatesMaxAllowedAndRemaining() {
        // input=100, syMax=0.85, tolerance=0.30
        // target = 100 × 0.85 = 85
        // maxAllowed = 85 × 1.30 = 110.5
        // alreadyReported = 60
        // remaining = 110.5 - 60 = 50.5
        WorkProcessTask t = task(20L, 1, "WP-GET");
        when(taskRepo.findByFactoryIdAndId("F006", 20L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-GET").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.8500")).build();
        when(processRepo.findById("WP-GET")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null); // default 30%
        when(reportRepo.findYieldReportsByTask("F006", 20L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("60")).build()
        ));

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 20L, new BigDecimal("100"));

        assertThat(dto.getWorkProcessTaskId()).isEqualTo(20L);
        assertThat(dto.getTargetQuantity()).isEqualByComparingTo("85");
        assertThat(dto.getStandardYieldMax()).isEqualByComparingTo("0.85");
        assertThat(dto.getUnit()).isEqualTo("kg");
        assertThat(dto.getAlreadyReported()).isEqualByComparingTo("60");
        assertThat(dto.getToleranceRate()).isEqualByComparingTo("0.30");
        assertThat(dto.getMaxAllowed()).isEqualByComparingTo("110.5");
        assertThat(dto.getRemaining()).isEqualByComparingTo("50.5");
        assertThat(dto.getMessage()).contains("60").contains("85").contains("110.5");
    }

    @Test
    void getLimits_noBase_nullTargetAndMax() {
        // standardYieldMax=null → targetQuantity/maxAllowed/remaining all null
        WorkProcessTask t = task(21L, 1, "WP-NULL");
        when(taskRepo.findByFactoryIdAndId("F006", 21L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-NULL").factoryId("F006")
                .unit("kg").standardYieldMax(null).build();
        when(processRepo.findById("WP-NULL")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 21L)).thenReturn(List.of());

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 21L, new BigDecimal("100"));

        assertThat(dto.getTargetQuantity()).isNull();
        assertThat(dto.getMaxAllowed()).isNull();
        assertThat(dto.getRemaining()).isNull();
        assertThat(dto.getMessage()).isNotBlank();
    }

    @Test
    void getYield_enrichesProcessNamesFromWorkProcess() {
        // 真实 calcSvc 从 reports 派生出 2 步 (task 24/25), processName 初始为 null
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(24L).processOrder(1)
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("935.5")).outputUnit("kg")
                .build();
        ProductionReport r2 = ProductionReport.builder()
                .workProcessTaskId(25L).processOrder(2)
                .inputQuantity(new BigDecimal("935.5")).inputUnit("kg")
                .outputQuantity(new BigDecimal("1262.9")).outputUnit("kg")
                .build();
        when(reportRepo.findYieldReportsByBatch("F006", 1897L)).thenReturn(List.of(r1, r2));
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any()))
                .thenReturn(List.of(task(24L, 1, "W1"), task(25L, 2, "W2")));
        WorkProcess w1 = new WorkProcess(); w1.setId("W1"); w1.setProcessName("处理");
        WorkProcess w2 = new WorkProcess(); w2.setId("W2"); w2.setProcessName("滚揉");
        when(processRepo.findAllById(any())).thenReturn(List.of(w1, w2));

        BatchYieldDTO dto = svc.getYield("F006", 1897L);

        assertThat(dto.getSteps()).extracting(StepYieldDTO::getProcessName)
                .containsExactly("处理", "滚揉");
    }
}
