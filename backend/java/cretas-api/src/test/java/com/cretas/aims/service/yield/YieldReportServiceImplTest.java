package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
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
    private MaterialBatchRepository materialBatchRepo;
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
        materialBatchRepo = mock(MaterialBatchRepository.class);
        objectMapper = new ObjectMapper();
        svc = new YieldReportServiceImpl(reportRepo, taskRepo, processRepo, calcSvc, processingService,
                factorySettingsRepo, materialBatchRepo, objectMapper);
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

    // ── A2b: recordMaterialInput 写 material_batch_refs ──────────────────────────

    /** 共用 helper: 为 recordMaterialInput 测试 setup task mock */
    private void setupMaterialInputTask() {
        WorkProcessTask t = task(30L, 1, "WP-MAT");
        when(taskRepo.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(t));
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
    }

    @Test
    void recordMaterialInput_writesMaterialBatchRefs_single() {
        setupMaterialInputTask();
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(101L); return r;
        });
        // materialBatch mb-uuid-aaa is NOT USED_UP, so autoSettle won't fire
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-aaa"); mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptQuantity(new BigDecimal("820")); mb.setUsedQuantity(new BigDecimal("520")); // remaining=300
        when(materialBatchRepo.findById("mb-uuid-aaa")).thenReturn(Optional.of(mb));

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("998"));
        req.setFeedInQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-aaa", new BigDecimal("520"), "kg")
        ));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getMaterialBatchRefs()).isNotNull().hasSize(1);
        Map<String, Object> ref = saved.getMaterialBatchRefs().get(0);
        assertThat(ref.get("materialBatchId")).isEqualTo("mb-uuid-aaa");
        assertThat(ref.get("quantity")).isEqualTo(new BigDecimal("520"));
        assertThat(ref.get("unit")).isEqualTo("kg");
    }

    @Test
    void recordMaterialInput_writesMaterialBatchRefs_multi() {
        setupMaterialInputTask();
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(102L); return r;
        });
        // both batches NOT USED_UP
        MaterialBatch mb1 = new MaterialBatch();
        mb1.setId("mb-uuid-aaa"); mb1.setStatus(MaterialBatchStatus.AVAILABLE);
        mb1.setReceiptQuantity(new BigDecimal("400")); mb1.setUsedQuantity(new BigDecimal("300")); // remaining=100
        MaterialBatch mb2 = new MaterialBatch();
        mb2.setId("mb-uuid-bbb"); mb2.setStatus(MaterialBatchStatus.AVAILABLE);
        mb2.setReceiptQuantity(new BigDecimal("400")); mb2.setUsedQuantity(new BigDecimal("200")); // remaining=200
        when(materialBatchRepo.findById("mb-uuid-aaa")).thenReturn(Optional.of(mb1));
        when(materialBatchRepo.findById("mb-uuid-bbb")).thenReturn(Optional.of(mb2));

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("500"));
        req.setFeedInQuantity(new BigDecimal("500"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-aaa", new BigDecimal("300"), "kg"),
                new MaterialBatchRef("mb-uuid-bbb", new BigDecimal("200"), "kg")
        ));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getMaterialBatchRefs()).isNotNull().hasSize(2);
        assertThat(saved.getMaterialBatchRefs().get(0).get("materialBatchId")).isEqualTo("mb-uuid-aaa");
        assertThat(saved.getMaterialBatchRefs().get(1).get("materialBatchId")).isEqualTo("mb-uuid-bbb");
    }

    // ── A2b: checkAndAutoSettle ───────────────────────────────────────────────────

    /** Build a ProductionReport with materialBatchRefs already set (simulates persisted report). */
    private ProductionReport reportWithBatchRefs(List<Map<String, Object>> refs) {
        ProductionReport r = ProductionReport.builder()
                .id(200L).factoryId("F006").batchId(1L).reportType("YIELD")
                .workerId(5L).settled(false)
                .build();
        r.setMaterialBatchRefs(refs);
        return r;
    }

    @Test
    void autoSettle_singleBatchUsedUp_settles() {
        setupMaterialInputTask();
        ArgumentCaptor<ProductionReport> saveCap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(saveCap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(201L); return r;
        });

        // trigger batch mb-uuid-aaa IS USED_UP (remainingQuantity=0)
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-aaa"); mb.setStatus(MaterialBatchStatus.USED_UP);
        mb.setReceiptQuantity(new BigDecimal("520")); mb.setUsedQuantity(new BigDecimal("520")); // remaining=0
        when(materialBatchRepo.findById("mb-uuid-aaa")).thenReturn(Optional.of(mb));

        // candidate report has refs=[{materialBatchId:"mb-uuid-aaa"}] — String value quoted in JSON
        ProductionReport candidate = reportWithBatchRefs(List.of(
                Map.of("materialBatchId", "mb-uuid-aaa", "quantity", new BigDecimal("520"), "unit", "kg")
        ));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-aaa\"}]")))
                .thenReturn(List.of(candidate));
        when(reportRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("998"));
        req.setFeedInQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-aaa", new BigDecimal("520"), "kg")
        ));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        // saveAll was called for settling the candidate
        ArgumentCaptor<List> settledCap = ArgumentCaptor.forClass(List.class);
        verify(reportRepo).saveAll(settledCap.capture());
        @SuppressWarnings("unchecked")
        List<ProductionReport> settled = (List<ProductionReport>) settledCap.getValue();
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).getSettled()).isTrue();
        assertThat(settled.get(0).getSettledAt()).isNotNull();
    }

    @Test
    void autoSettle_multiBatch_partialUsedUp_notSettled() {
        setupMaterialInputTask();
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(202L); return r;
        });

        // batch mb-uuid-aaa USED_UP, batch mb-uuid-bbb still has remaining
        MaterialBatch mbAaa = new MaterialBatch();
        mbAaa.setId("mb-uuid-aaa"); mbAaa.setStatus(MaterialBatchStatus.USED_UP);
        mbAaa.setReceiptQuantity(new BigDecimal("300")); mbAaa.setUsedQuantity(new BigDecimal("300")); // remaining=0
        MaterialBatch mbBbb = new MaterialBatch();
        mbBbb.setId("mb-uuid-bbb"); mbBbb.setStatus(MaterialBatchStatus.AVAILABLE);
        mbBbb.setReceiptQuantity(new BigDecimal("400")); mbBbb.setUsedQuantity(new BigDecimal("200")); // remaining=200
        when(materialBatchRepo.findById("mb-uuid-aaa")).thenReturn(Optional.of(mbAaa));
        when(materialBatchRepo.findById("mb-uuid-bbb")).thenReturn(Optional.of(mbBbb));

        // candidate report refs BOTH batches; mb-uuid-bbb has remaining → allRefsUsedUp=false → not settled
        ProductionReport candidate = reportWithBatchRefs(List.of(
                Map.of("materialBatchId", "mb-uuid-aaa", "quantity", new BigDecimal("300")),
                Map.of("materialBatchId", "mb-uuid-bbb", "quantity", new BigDecimal("200"))
        ));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-aaa\"}]")))
                .thenReturn(List.of(candidate));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-bbb\"}]")))
                .thenReturn(List.of(candidate));

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("500"));
        req.setFeedInQuantity(new BigDecimal("500"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-aaa", new BigDecimal("300"), "kg"),
                new MaterialBatchRef("mb-uuid-bbb", new BigDecimal("200"), "kg")
        ));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        // saveAll should NOT be called (no settling happened)
        verify(reportRepo, never()).saveAll(any());
        assertThat(candidate.getSettled()).isFalse();
    }

    @Test
    void autoSettle_multiBatch_allUsedUp_settles() {
        setupMaterialInputTask();
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(203L); return r;
        });

        // both batches USED_UP
        MaterialBatch mbAaa = new MaterialBatch();
        mbAaa.setId("mb-uuid-aaa"); mbAaa.setStatus(MaterialBatchStatus.USED_UP);
        mbAaa.setReceiptQuantity(new BigDecimal("300")); mbAaa.setUsedQuantity(new BigDecimal("300")); // remaining=0
        MaterialBatch mbBbb = new MaterialBatch();
        mbBbb.setId("mb-uuid-bbb"); mbBbb.setStatus(MaterialBatchStatus.USED_UP);
        mbBbb.setReceiptQuantity(new BigDecimal("200")); mbBbb.setUsedQuantity(new BigDecimal("200")); // remaining=0
        when(materialBatchRepo.findById("mb-uuid-aaa")).thenReturn(Optional.of(mbAaa));
        when(materialBatchRepo.findById("mb-uuid-bbb")).thenReturn(Optional.of(mbBbb));

        ProductionReport candidate = reportWithBatchRefs(List.of(
                Map.of("materialBatchId", "mb-uuid-aaa", "quantity", new BigDecimal("300")),
                Map.of("materialBatchId", "mb-uuid-bbb", "quantity", new BigDecimal("200"))
        ));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-aaa\"}]")))
                .thenReturn(List.of(candidate));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-bbb\"}]")))
                .thenReturn(List.of(candidate));
        when(reportRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("500"));
        req.setFeedInQuantity(new BigDecimal("500"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-aaa", new BigDecimal("300"), "kg"),
                new MaterialBatchRef("mb-uuid-bbb", new BigDecimal("200"), "kg")
        ));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        ArgumentCaptor<List> settledCap = ArgumentCaptor.forClass(List.class);
        // saveAll called at least once (may be called once per batch trigger, but candidate deduplicated)
        verify(reportRepo, atLeastOnce()).saveAll(settledCap.capture());
        // The candidate must end up settled
        assertThat(candidate.getSettled()).isTrue();
        assertThat(candidate.getSettledAt()).isNotNull();
    }

    @Test
    void autoSettle_noRefs_skips() {
        setupMaterialInputTask();
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(204L); return r;
        });

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("998"));
        req.setFeedInQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(null);  // no refs → no autoSettle

        svc.recordMaterialInput("F006", 1L, 5L, req);

        // No saveAll for settling, no materialBatchRepo calls
        verify(reportRepo, never()).saveAll(any());
        verify(materialBatchRepo, never()).findById(anyString());
    }

    // ── Task 4: autoSettleByMaterialBatch 端点测试 ──────────────────────────────

    @Test
    void autoSettleByMaterialBatch_returnsSettledCount() {
        // batch mb-uuid-ccc is USED_UP; 1 candidate report gets settled
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-ccc"); mb.setStatus(MaterialBatchStatus.USED_UP);
        mb.setReceiptQuantity(new BigDecimal("300")); mb.setUsedQuantity(new BigDecimal("300"));
        when(materialBatchRepo.findById("mb-uuid-ccc")).thenReturn(Optional.of(mb));

        ProductionReport candidate = reportWithBatchRefs(List.of(
                Map.of("materialBatchId", "mb-uuid-ccc", "quantity", new BigDecimal("300"), "unit", "kg")
        ));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-ccc\"}]")))
                .thenReturn(List.of(candidate));
        when(reportRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> result = svc.autoSettleByMaterialBatch("F006", 1L, "mb-uuid-ccc");

        assertThat(result.get("settledCount")).isEqualTo(1);
        assertThat(candidate.getSettled()).isTrue();
        assertThat(candidate.getSettledAt()).isNotNull();
    }

    @Test
    void autoSettleByMaterialBatch_batchNotUsedUp_returnsZero() {
        // batch mb-uuid-ddd still AVAILABLE → no settle
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-ddd"); mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptQuantity(new BigDecimal("300")); mb.setUsedQuantity(new BigDecimal("100"));
        when(materialBatchRepo.findById("mb-uuid-ddd")).thenReturn(Optional.of(mb));

        Map<String, Object> result = svc.autoSettleByMaterialBatch("F006", 1L, "mb-uuid-ddd");

        assertThat(result.get("settledCount")).isEqualTo(0);
        verify(reportRepo, never()).findUnsettledYieldContainingMaterialBatch(any(), any(), any());
    }

    @Test
    void autoSettle_batchExpiredNotUsedUp_doesNotSettle() {
        // Fix 1 regression: EXPIRED batch with remaining=0 must NOT trigger auto-settle.
        // "原料用完自动结清" fires ONLY on status==USED_UP (正常耗尽), not on EXPIRED/DEFECTIVE/SCRAPPED.
        setupMaterialInputTask();
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(205L); return r;
        });

        // batch mb-uuid-exp has status EXPIRED and remaining=0 (e.g. expired and fully consumed)
        // UUID-style id confirms Long regression would be caught: "mb-uuid-exp" cannot be parsed as Long.
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-exp"); mb.setStatus(MaterialBatchStatus.EXPIRED);
        mb.setReceiptQuantity(new BigDecimal("100")); mb.setUsedQuantity(new BigDecimal("100")); // remaining=0
        when(materialBatchRepo.findById("mb-uuid-exp")).thenReturn(Optional.of(mb));

        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("100"));
        req.setFeedInQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-exp", new BigDecimal("100"), "kg")
        ));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        // checkAndAutoSettle sees status != USED_UP → returns immediately, saveAll never called
        verify(reportRepo, never()).saveAll(any());
    }
}
