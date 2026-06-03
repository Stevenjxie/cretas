package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.OrderYieldSummaryDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
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
    private ProductTypeRepository productTypeRepo;
    private ProductionBatchRepository productionBatchRepo;
    private ProductionPlanRepository productionPlanRepo;
    private SemiFinishedInventoryRepository wipRepo;
    private BatchLineageEdgeRepository lineageEdgeRepo;
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
        productTypeRepo = mock(ProductTypeRepository.class);
        productionBatchRepo = mock(ProductionBatchRepository.class);
        productionPlanRepo = mock(ProductionPlanRepository.class);
        wipRepo = mock(SemiFinishedInventoryRepository.class);
        lineageEdgeRepo = mock(BatchLineageEdgeRepository.class);
        objectMapper = new ObjectMapper();
        // default: no source WIP found (向后兼容旧测试: sourceWipNo=null 不查 WIP)
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(lineageEdgeRepo.save(any(BatchLineageEdge.class))).thenAnswer(i -> i.getArgument(0));
        svc = new YieldReportServiceImpl(reportRepo, taskRepo, processRepo, calcSvc, processingService,
                factorySettingsRepo, materialBatchRepo, productTypeRepo, productionBatchRepo,
                productionPlanRepo, wipRepo, lineageEdgeRepo, objectMapper);
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

    // ── Issue #1: submitReport 携带 materialBatchRefs (一单报完, 不再双调) ──────────

    @Test
    void submitReport_writesMaterialBatchRefs_andAutoSettles() {
        // Arrange: task 40 (首道), batch USED_UP → autoSettle fires, candidate report gets settled
        WorkProcessTask t = task(40L, 1, "WP-MREF");
        t.setProductTypeId("PT-200");
        when(taskRepo.findByFactoryIdAndId("F006", 40L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-MREF")).thenReturn(Optional.empty());
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", 40L)).thenReturn(List.of()); // 首条

        ArgumentCaptor<ProductionReport> reportCap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(reportCap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(301L); return r;
        });

        // material batch mb-uuid-x is USED_UP → triggers autoSettle
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-uuid-x"); mb.setStatus(MaterialBatchStatus.USED_UP);
        mb.setReceiptQuantity(new BigDecimal("500")); mb.setUsedQuantity(new BigDecimal("500"));
        when(materialBatchRepo.findById("mb-uuid-x")).thenReturn(Optional.of(mb));

        // candidate report that was previously created (e.g. older submit)
        ProductionReport candidate = reportWithBatchRefs(List.of(
                Map.of("materialBatchId", "mb-uuid-x", "quantity", new BigDecimal("500"), "unit", "kg")
        ));
        when(reportRepo.findUnsettledYieldContainingMaterialBatch(
                eq("F006"), eq(1L), eq("[{\"materialBatchId\":\"mb-uuid-x\"}]")))
                .thenReturn(List.of(candidate));
        when(reportRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // Act: submitReport with materialBatchRefs (no separate recordMaterialInput call)
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(40L);
        req.setInputQuantity(new BigDecimal("500"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("382"));
        req.setOutputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-uuid-x", new BigDecimal("500"), "kg")
        ));

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        // Assert: report saved with materialBatchRefs
        assertThat(out.get("reportId")).isEqualTo(301L);
        ProductionReport saved = reportCap.getValue();
        assertThat(saved.getMaterialBatchRefs()).isNotNull().hasSize(1);
        assertThat(saved.getMaterialBatchRefs().get(0).get("materialBatchId")).isEqualTo("mb-uuid-x");
        assertThat(saved.getMaterialBatchRefs().get(0).get("quantity")).isEqualTo(new BigDecimal("500"));

        // Assert: autoSettle fired — saveAll called for candidate
        ArgumentCaptor<List> settledCap = ArgumentCaptor.forClass(List.class);
        verify(reportRepo).saveAll(settledCap.capture());
        @SuppressWarnings("unchecked")
        List<ProductionReport> settled = (List<ProductionReport>) settledCap.getValue();
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).getSettled()).isTrue();
        assertThat(settled.get(0).getSettledAt()).isNotNull();
    }

    // ── 三阶段报工 (单元1): submitReport 按 reportKind 字段隔离 ──────────────────────

    /** 共用 helper: 三阶段报工 task 60 (首道, WP-PHASE, syMax 配置) mock setup. */
    private void setupPhaseTask(BigDecimal syMax) {
        WorkProcessTask t = task(60L, 1, "WP-PHASE");
        t.setProductTypeId("PT-PH");
        when(taskRepo.findByFactoryIdAndId("F006", 60L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-PHASE").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("30"))
                .standardYieldMax(syMax).build();
        when(processRepo.findById("WP-PHASE")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", 60L)).thenReturn(List.of());
    }

    @Test
    void submitReport_inputKind_forcesOutputNull_computesMaterialCost_persistsKind() {
        setupPhaseTask(new BigDecimal("1.0000"));
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(601L); return r;
        });
        // material batch with price → materialCost computed
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-ph"); mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setUnitPrice(new BigDecimal("2.00"));
        when(materialBatchRepo.findById("mb-ph")).thenReturn(Optional.of(mb));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        req.setReportKind("INPUT");
        req.setInputQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("980"));   // 误填 → 必须被强制 null
        req.setOutputUnit("kg");
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-ph", new BigDecimal("998"), "kg")));

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("reportId")).isEqualTo(601L);
        ProductionReport saved = cap.getValue();
        assertThat(saved.getReportKind()).isEqualTo("INPUT");
        assertThat(saved.getInputQuantity()).isEqualByComparingTo("998");
        assertThat(saved.getOutputQuantity()).isNull();          // 强制 null (即便请求带了)
        assertThat(saved.getMaterialCost()).isEqualByComparingTo("1996.00");  // 998 × 2.00
        assertThat(saved.getLaborCost()).isNull();               // INPUT 不算人工
        assertThat(saved.getMaterialBatchRefs()).isNotNull().hasSize(1);
        // INPUT 阶段不产出 → 不应 upsert WIP
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
    }

    @Test
    void submitReport_segmentKind_forcesInputOutputNull_computesLaborCost() {
        setupPhaseTask(new BigDecimal("1.0000"));
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(602L); return r;
        });

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        req.setReportKind("SEGMENT");
        req.setInputQuantity(new BigDecimal("100"));   // 误填 → 强制 null
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90"));   // 误填 → 强制 null
        req.setOutputUnit("kg");
        YieldReportRequest.LaborSegment seg = new YieldReportRequest.LaborSegment();
        seg.setStartTime("08:00"); seg.setEndTime("10:00"); seg.setHeadcount(3);   // 2h × 3 = 6 person-h
        req.setLaborSegments(List.of(seg));

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("reportId")).isEqualTo(602L);
        ProductionReport saved = cap.getValue();
        assertThat(saved.getReportKind()).isEqualTo("SEGMENT");
        assertThat(saved.getInputQuantity()).isNull();
        assertThat(saved.getOutputQuantity()).isNull();
        assertThat(saved.getMaterialCost()).isNull();
        assertThat(saved.getLaborCost()).isEqualByComparingTo("180.00");   // 6 person-h × 30
        assertThat(saved.getLaborSegments()).isNotNull().hasSize(1);
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
    }

    @Test
    void submitReport_outputKind_forcesInputNull_keepsOutput_upsertsWip() {
        setupPhaseTask(new BigDecimal("1.5000"));   // syMax 高, 不触发超收
        // task-accumulated cost from prior INPUT/SEGMENT reports (for OUTPUT WIP cost roll-up)
        when(reportRepo.findYieldReportsByTask("F006", 60L)).thenReturn(List.of(
                ProductionReport.builder().reportKind("INPUT").materialCost(new BigDecimal("1000.00")).build(),
                ProductionReport.builder().reportKind("SEGMENT").laborCost(new BigDecimal("180.00")).build()
        ));
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(603L); return r;
        });

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        req.setReportKind("OUTPUT");
        req.setInputQuantity(new BigDecimal("998"));   // 误填 → 强制 null
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("980"));
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("reportId")).isEqualTo(603L);
        ProductionReport saved = cap.getValue();
        assertThat(saved.getReportKind()).isEqualTo("OUTPUT");
        assertThat(saved.getInputQuantity()).isNull();           // 强制 null
        assertThat(saved.getOutputQuantity()).isEqualByComparingTo("980");
        assertThat(saved.getLaborCost()).isNull();               // 成本在 INPUT/SEGMENT 报工上
        assertThat(saved.getMaterialCost()).isNull();
        // OUTPUT 阶段产出锁定 → upsert WIP
        ArgumentCaptor<SemiFinishedInventory> wipCap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        verify(wipRepo).save(wipCap.capture());
        SemiFinishedInventory wip = wipCap.getValue();
        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("980");
        // WIP 成本滚动用整道汇总 (Σ INPUT materialCost 1000 + Σ SEGMENT laborCost 180 = 1180), 非本 OUTPUT 报工(null)
        assertThat(wip.getAccumulatedCost()).isEqualByComparingTo("1180.00");
    }

    @Test
    void submitReport_nullKind_legacyFullBehaviorUnchanged() {
        // 回归守卫: reportKind=null → 旧式整合, 投入+产出全保留, 成本全算, WIP upsert
        setupPhaseTask(new BigDecimal("1.5000"));
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-leg"); mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setUnitPrice(new BigDecimal("2.00"));
        when(materialBatchRepo.findById("mb-leg")).thenReturn(Optional.of(mb));
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(604L); return r;
        });

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        // reportKind not set → null (legacy)
        req.setInputQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("980"));
        req.setOutputUnit("kg");
        req.setWorkMinutes(120);
        req.setWorkerCount(3);
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-leg", new BigDecimal("998"), "kg")));

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getReportKind()).isNull();
        assertThat(saved.getInputQuantity()).isEqualByComparingTo("998");   // 投入保留
        assertThat(saved.getOutputQuantity()).isEqualByComparingTo("980");  // 产出保留
        assertThat(saved.getLaborCost()).isEqualByComparingTo("180.00");    // 3 人 × 2h × 30 = 180
        assertThat(saved.getMaterialCost()).isEqualByComparingTo("1996.00"); // 998 × 2.00
        // 旧式: 有产出 → WIP upsert
        verify(wipRepo).save(any(SemiFinishedInventory.class));
        // yieldRate 正常返回 (旧式行为不变)
        assertThat(out.get("yieldRate")).isNotNull();
    }

    @Test
    void submitReport_outputKind_missingOutput_throws400() {
        // OUTPUT 阶段缺产出量 → 400 (产出阶段必填 outputQuantity)
        setupPhaseTask(new BigDecimal("1.5000"));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        req.setReportKind("OUTPUT");
        req.setInputUnit("kg");
        req.setOutputUnit("kg");
        // outputQuantity 未设 → null

        assertThatThrownBy(() -> svc.submitReport("F006", 1L, 5L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(reportRepo, never()).save(any(ProductionReport.class));
    }

    // ── P0-2: kg/份 单位换算 ──────────────────────────────────────────────────────

    @Test
    void getYield_resolvesGramsPerUnit_crossUnitCumulativeNonNull() {
        // 末道 kg→份 (998kg → 382份), 产品配 gramsPerUnit=120 → cumulative = (382×120/1000)/998 折算
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(50L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("935.5")).outputUnit("kg")
                .build();
        ProductionReport r2 = ProductionReport.builder()
                .workProcessTaskId(51L).processOrder(2).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("935.5")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("份")
                .build();
        when(reportRepo.findYieldReportsByBatch("F006", 7L)).thenReturn(List.of(r1, r2));
        ProductType pt = new ProductType();
        pt.setId("PT-LU"); pt.setFactoryId("F006"); pt.setGramsPerUnit(new BigDecimal("120"));
        when(productTypeRepo.findByIdAndFactoryId("PT-LU", "F006")).thenReturn(Optional.of(pt));
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        BatchYieldDTO dto = svc.getYield("F006", 7L);

        // cumulative = (382 × 120 / 1000) / 998 = 45.84 / 998 = 0.0459 (非 null = 折算成功)
        assertThat(dto.getCumulativeYieldRate()).isNotNull();
        verify(productTypeRepo).findByIdAndFactoryId("PT-LU", "F006");
    }

    @Test
    void getYield_noGramsPerUnit_crossUnitCumulativeNull() {
        // 末道 kg→份 但产品无 gramsPerUnit → cumulative 保持 null (诚实, 不臆造)
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(52L).processOrder(1).productTypeId("PT-NOGRAM")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("935.5")).outputUnit("kg")
                .build();
        ProductionReport r2 = ProductionReport.builder()
                .workProcessTaskId(53L).processOrder(2).productTypeId("PT-NOGRAM")
                .inputQuantity(new BigDecimal("935.5")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("份")
                .build();
        when(reportRepo.findYieldReportsByBatch("F006", 8L)).thenReturn(List.of(r1, r2));
        ProductType pt = new ProductType();
        pt.setId("PT-NOGRAM"); pt.setFactoryId("F006"); pt.setGramsPerUnit(null);
        when(productTypeRepo.findByIdAndFactoryId("PT-NOGRAM", "F006")).thenReturn(Optional.of(pt));
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        BatchYieldDTO dto = svc.getYield("F006", 8L);

        assertThat(dto.getCumulativeYieldRate()).isNull();
        assertThat(dto.getLastStepOutputUnit()).isEqualTo("份");
    }

    @Test
    void settleDay_triggerComplete_passesLastOutputUnitAsFinishedUnit() {
        // settle + triggerComplete=true: completeProduction 收到 finishedUnit=份 (末道产出单位)
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(60L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("935.5")).outputUnit("kg")
                .build();
        ProductionReport r2 = ProductionReport.builder()
                .workProcessTaskId(61L).processOrder(2).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("935.5")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("份")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(9L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 9L)).thenReturn(List.of(r1, r2));
        when(productTypeRepo.findByIdAndFactoryId("PT-LU", "F006")).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        // P1-1: 批次 IN_PROGRESS → 可完工
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepo.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(batch));
        when(processingService.completeProduction(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductionBatch());

        Map<String, Object> out = svc.settleDay("F006", 9L, 5L, null, true);

        assertThat(out.get("completed")).isEqualTo(true);
        // 末道产出 382 份 → completeProduction(.., 382, 382, 0, "份")
        verify(processingService).completeProduction(eq("F006"), eq("9"),
                eq(new BigDecimal("382")), eq(new BigDecimal("382")), eq(BigDecimal.ZERO), eq("份"));
    }

    @Test
    void settleDay_noTriggerComplete_doesNotComplete() {
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(62L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(10L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 10L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        Map<String, Object> out = svc.settleDay("F006", 10L, 5L, null, false);

        assertThat(out.get("completed")).isEqualTo(false);
        verify(processingService, never()).completeProduction(anyString(), anyString(), any(), any(), any());
        verify(processingService, never()).completeProduction(anyString(), anyString(), any(), any(), any(), any());
    }

    // ── P1-1: 完工入库 + 回填生产计划 ─────────────────────────────────────────────

    @Test
    void settleDay_triggerComplete_batchPlanned_skipsComplete_settlesStill() {
        // P1-1 1c: 批次 PLANNED (工人没点开始生产) → 不调 completeProduction (避免 409 污染结清),
        // completed=false + completeError 非空, 结清记录仍 saveAll (settled=true)
        ProductionReport unsettled = ProductionReport.builder()
                .workProcessTaskId(70L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .settled(false).build();
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(70L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(11L), any())).thenReturn(List.of(unsettled));
        when(reportRepo.findYieldReportsByBatch("F006", 11L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.PLANNED);
        when(productionBatchRepo.findByIdAndFactoryId(11L, "F006")).thenReturn(Optional.of(batch));

        Map<String, Object> out = svc.settleDay("F006", 11L, 5L, null, true);

        assertThat(out.get("completed")).isEqualTo(false);
        assertThat(out.get("completeError")).asString().contains("不允许完工");
        // 结清仍成功: settled 标记被写, saveAll 被调
        assertThat(unsettled.getSettled()).isTrue();
        verify(reportRepo).saveAll(any());
        // completeProduction 从未被调 (无 409 抛出)
        verify(processingService, never()).completeProduction(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void settleDay_triggerComplete_lastOutputZero_doesNotComplete() {
        // 末道产出为 0 → 不触发完工
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(71L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(BigDecimal.ZERO).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(12L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 12L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        Map<String, Object> out = svc.settleDay("F006", 12L, 5L, null, true);

        assertThat(out.get("completed")).isEqualTo(false);
        verify(processingService, never()).completeProduction(anyString(), anyString(), any(), any(), any(), any());
        // lastOutput=0 → 完工 path 不调 completeProduction; 未完工 → D3 余料提示不触发 (findRemainingWip 不查)
        // (注: getYield 的 C 进行中标注会查批次, 故不再断言 findByIdAndFactoryId never — Wave 3 新契约)
        verify(wipRepo, never()).findRemainingWip(anyString(), any());
        assertThat(out.get("wipRemainingHint")).isNull();
    }

    @Test
    void settleDay_triggerComplete_batchCompleted_idempotent_skipsComplete() {
        // P1-1 幂等: 批次已 COMPLETED → 不重复 completeProduction (防 PP actualQuantity 重复累加)
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(72L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(13L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 13L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(productionBatchRepo.findByIdAndFactoryId(13L, "F006")).thenReturn(Optional.of(batch));

        Map<String, Object> out = svc.settleDay("F006", 13L, 5L, null, true);

        assertThat(out.get("completed")).isEqualTo(false);
        assertThat(out.get("completeError")).asString().contains("不允许完工");
        verify(processingService, never()).completeProduction(anyString(), anyString(), any(), any(), any(), any());
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

    // ── P1-3 (G4): 工时/人数采集 ──────────────────────────────────────────────────

    @Test
    void submitReport_storesWorkerCountAndMinutes() {
        // 报工带 workerCount=3, workMinutes=120 → 保存的 report total_workers=3, total_work_minutes=120
        WorkProcessTask t = task(10L, 1, "WP-LU");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-LU")).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of());
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("80"));
        req.setOutputUnit("kg");
        req.setWorkerCount(3);
        req.setWorkMinutes(120);

        svc.submitReport("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getTotalWorkers()).isEqualTo(3);
        assertThat(saved.getTotalWorkMinutes()).isEqualTo(120);
    }

    @Test
    void submitReport_nullWorkerCount_storesNull_backwardCompatible() {
        // 不带 workerCount/workMinutes → total_workers/total_work_minutes 为 null (向后兼容, 不报错)
        WorkProcessTask t = task(10L, 1, "WP-LU");
        when(taskRepo.findByFactoryIdAndId("F006", 10L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-LU")).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of());
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(99L); return r;
        });

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(10L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("80"));
        req.setOutputUnit("kg");
        // workerCount / workMinutes 不设 → null

        svc.submitReport("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getTotalWorkers()).isNull();
        assertThat(saved.getTotalWorkMinutes()).isNull();
    }

    @Test
    void getYield_aggregatesWorkMinutesAndWorkers_toBatchTotal() {
        // 两道各带工时/人数 → batch totalWorkMinutes/totalWorkers = Σ steps
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(80L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("935.5")).outputUnit("kg")
                .totalWorkMinutes(120).totalWorkers(2)
                .build();
        ProductionReport r2 = ProductionReport.builder()
                .workProcessTaskId(81L).processOrder(2).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("935.5")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("kg")
                .totalWorkMinutes(90).totalWorkers(3)
                .build();
        when(reportRepo.findYieldReportsByBatch("F006", 14L)).thenReturn(List.of(r1, r2));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        BatchYieldDTO dto = svc.getYield("F006", 14L);

        assertThat(dto.getTotalWorkMinutes()).isEqualTo(210);
        assertThat(dto.getTotalWorkers()).isEqualTo(5);
        assertThat(dto.getSteps().get(0).getTotalWorkMinutes()).isEqualTo(120);
        assertThat(dto.getSteps().get(0).getTotalWorkers()).isEqualTo(2);
    }

    // ── G6/G7 Wave 2: WIP 产出 / 领用扣减 / 防呆 ─────────────────────────────────

    /** 共用: setup 一道 task 报工成功保存 (mock report save 返 id). */
    private void setupWipSubmitTask(WorkProcessTask t) {
        when(taskRepo.findByFactoryIdAndId("F006", t.getId())).thenReturn(Optional.of(t));
        when(processRepo.findById(anyString())).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", t.getId())).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(900L); return r;
        });
    }

    @Test
    void submitReport_producesWip_createsNewRowFromOutput() {
        // 道1 首次报工产出 935.5kg → 建一笔新 WIP 行 (produced=available=935.5, consumed=0)
        WorkProcessTask t = task(100L, 1, "WP-P1");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        ArgumentCaptor<SemiFinishedInventory> wipCap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        when(wipRepo.save(wipCap.capture())).thenAnswer(i -> i.getArgument(0));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(100L);
        req.setInputQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("935.5"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        SemiFinishedInventory wip = wipCap.getValue();
        // 工序批次号 = {产品码}-B{批次}-S{工序序}-{taskId}
        assertThat(wip.getIntermediateBatchNo()).isEqualTo("PT-LU-B1-S1-100");
        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("935.5");
        assertThat(wip.getConsumedQuantity()).isEqualByComparingTo("0");
        assertThat(wip.getAvailableQuantity()).isEqualByComparingTo("935.5");
        assertThat(wip.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
        assertThat(wip.getUnit()).isEqualTo("kg");
        assertThat(wip.getProcessOrder()).isEqualTo(1);
    }

    @Test
    void submitReport_producesWip_crossDayAccumulatesProducedAndAvailable() {
        // 跨天: 同 task 第二次报工产出 200kg, 已有 WIP 行 produced=500/consumed=100/available=400
        // → produced=700, available=700-100=600
        WorkProcessTask t = task(101L, 1, "WP-P2");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        // 第二条报工: 已有 1 条 → isFirstReportForTask=false (intermediateBatchNo null on report,
        // 但 WIP upsert 用 generateBatchNo 重派生稳定键命中已有行)
        when(reportRepo.findYieldReportsByTask("F006", 101L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("500")).build()
        ));
        SemiFinishedInventory existing = SemiFinishedInventory.builder()
                .id(7L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-101").processOrder(1)
                .producedQuantity(new BigDecimal("500"))
                .consumedQuantity(new BigDecimal("100"))
                .availableQuantity(new BigDecimal("400"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-101"))
                .thenReturn(Optional.of(existing));
        ArgumentCaptor<SemiFinishedInventory> wipCap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        when(wipRepo.save(wipCap.capture())).thenAnswer(i -> i.getArgument(0));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(101L);
        req.setInputQuantity(new BigDecimal("0"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("200"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        SemiFinishedInventory wip = wipCap.getValue();
        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("700");  // 500+200
        assertThat(wip.getConsumedQuantity()).isEqualByComparingTo("100");  // 不变
        assertThat(wip.getAvailableQuantity()).isEqualByComparingTo("600"); // 700-100
    }

    @Test
    void submitReport_consumesSourceWip_decrementsAvailableAndStatus() {
        // 道2 领用源 WIP (sourceWipNo): available 500 → 领 500 → consumed=500, available=0 → DEPLETED
        WorkProcessTask t = task(102L, 2, "WP-P3");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .id(8L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .producedQuantity(new BigDecimal("500"))
                .consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("500"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-100"))
                .thenReturn(Optional.of(source));
        // 本道产出 WIP 行 (不同 key) 不影响断言: 让 produced upsert 找不到行新建
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S2-102"))
                .thenReturn(Optional.empty());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(102L);
        req.setSourceWipNo("PT-LU-B1-S1-100");
        req.setInputQuantity(new BigDecimal("500"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("480"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        // source WIP 被扣减并标 DEPLETED
        assertThat(source.getConsumedQuantity()).isEqualByComparingTo("500");
        assertThat(source.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(source.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
        // lineage 边写入 (副产物)
        verify(lineageEdgeRepo).save(any(BatchLineageEdge.class));
    }

    @Test
    void submitReport_consumeSourceWip_partialLeavesCarryover() {
        // G7 部分领用: available 500 → 领 300 → consumed=300, available=200 (结余), 仍 AVAILABLE
        WorkProcessTask t = task(103L, 2, "WP-P4");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .id(9L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .producedQuantity(new BigDecimal("500"))
                .consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("500"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-100"))
                .thenReturn(Optional.of(source));
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S2-103"))
                .thenReturn(Optional.empty());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(103L);
        req.setSourceWipNo("PT-LU-B1-S1-100");
        req.setInputQuantity(new BigDecimal("300"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("290"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(source.getConsumedQuantity()).isEqualByComparingTo("300");
        assertThat(source.getAvailableQuantity()).isEqualByComparingTo("200");  // 结余
        assertThat(source.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    void submitReport_overDrawSourceWip_throws409_doesNotSave() {
        // 防呆 Rule 1: available 200, 领 250 > 200 → 409 WIP_INSUFFICIENT, 报工不落库
        WorkProcessTask t = task(104L, 2, "WP-P5");
        when(taskRepo.findByFactoryIdAndId("F006", 104L)).thenReturn(Optional.of(t));
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .id(10L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .producedQuantity(new BigDecimal("200"))
                .consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("200"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-100"))
                .thenReturn(Optional.of(source));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(104L);
        req.setSourceWipNo("PT-LU-B1-S1-100");
        req.setInputQuantity(new BigDecimal("250"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("240"));
        req.setOutputUnit("kg");

        assertThatThrownBy(() -> svc.submitReport("F006", 1L, 5L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("WIP_INSUFFICIENT");
                    assertThat(be.getActionHint()).contains("余额仅").contains("200").contains("250");
                });
        // 报工不落库, WIP 不被扣减
        verify(reportRepo, never()).save(any(ProductionReport.class));
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        assertThat(source.getConsumedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void submitReport_crossUnitSourceWip_throws409_doesNotSave() {
        // 跨单位防呆: WIP 单位 kg, 本道 inputUnit 份 → 409 WIP_UNIT_MISMATCH, 报工不落库, WIP 不扣减
        WorkProcessTask t = task(108L, 2, "WP-XU");
        when(taskRepo.findByFactoryIdAndId("F006", 108L)).thenReturn(Optional.of(t));
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .id(11L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .producedQuantity(new BigDecimal("500"))
                .consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("500"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-100"))
                .thenReturn(Optional.of(source));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(108L);
        req.setSourceWipNo("PT-LU-B1-S1-100");
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("份");   // ← 与 WIP 单位 kg 不一致
        req.setOutputQuantity(new BigDecimal("90"));
        req.setOutputUnit("份");

        assertThatThrownBy(() -> svc.submitReport("F006", 1L, 5L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("WIP_UNIT_MISMATCH");
                    assertThat(be.getActionHint()).contains("kg").contains("份");
                });
        // 报工不落库, WIP 不被扣减 (校验在保存前)
        verify(reportRepo, never()).save(any(ProductionReport.class));
        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        assertThat(source.getConsumedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void submitReport_sameUnitSourceWip_consumesNormally_noUnitMismatch() {
        // 同单位 (WIP kg + 本道 kg): 单位校验通过, 正常扣减 (防呆不误报)
        WorkProcessTask t = task(109L, 2, "WP-SU");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .id(12L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .producedQuantity(new BigDecimal("500"))
                .consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("500"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-100"))
                .thenReturn(Optional.of(source));
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S2-109"))
                .thenReturn(Optional.empty());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(109L);
        req.setSourceWipNo("PT-LU-B1-S1-100");
        req.setInputQuantity(new BigDecimal("300"));
        req.setInputUnit("kg");   // ← 与 WIP 单位 kg 一致
        req.setOutputQuantity(new BigDecimal("290"));
        req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("reportId")).isEqualTo(900L);
        assertThat(source.getConsumedQuantity()).isEqualByComparingTo("300");
        assertThat(source.getAvailableQuantity()).isEqualByComparingTo("200");
    }

    @Test
    void submitReport_nullInputUnitSourceWip_skipsUnitCheck_consumes() {
        // inputUnit 为空 → 跳过单位校验 (向后兼容旧客户端不传单位), 仍按余额扣减
        WorkProcessTask t = task(112L, 2, "WP-NU");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .id(13L).factoryId("F006").batchId(1L)
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .producedQuantity(new BigDecimal("500"))
                .consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("500"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S1-100"))
                .thenReturn(Optional.of(source));
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-LU-B1-S2-112"))
                .thenReturn(Optional.empty());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(112L);
        req.setSourceWipNo("PT-LU-B1-S1-100");
        req.setInputQuantity(new BigDecimal("200"));
        req.setInputUnit(null);   // ← 不传单位 → 跳过单位校验
        req.setOutputQuantity(new BigDecimal("190"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(source.getConsumedQuantity()).isEqualByComparingTo("200");
        assertThat(source.getAvailableQuantity()).isEqualByComparingTo("300");
    }

    @Test
    void submitReport_unknownSourceWipNo_throws404() {
        WorkProcessTask t = task(105L, 2, "WP-P6");
        when(taskRepo.findByFactoryIdAndId("F006", 105L)).thenReturn(Optional.of(t));
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("NO-SUCH-WIP"))
                .thenReturn(Optional.empty());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(105L);
        req.setSourceWipNo("NO-SUCH-WIP");
        req.setInputQuantity(new BigDecimal("10"));
        req.setOutputQuantity(new BigDecimal("9"));

        assertThatThrownBy(() -> svc.submitReport("F006", 1L, 5L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404));
        verify(reportRepo, never()).save(any(ProductionReport.class));
    }

    @Test
    void submitReport_nullSourceWipNo_backwardCompatible_noWipLookupForConsume() {
        // sourceWipNo=null → 不查源 WIP (走旧路径), 仍产出进 WIP (G6 对所有报工生效)
        WorkProcessTask t = task(106L, 1, "WP-P7");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);
        ArgumentCaptor<SemiFinishedInventory> wipCap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        when(wipRepo.save(wipCap.capture())).thenAnswer(i -> i.getArgument(0));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(106L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("80"));
        req.setOutputUnit("kg");
        // sourceWipNo 不设 → null

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("reportId")).isEqualTo(900L);
        verify(reportRepo).save(any(ProductionReport.class));  // 旧路径正常保存
        // 仅产出 WIP 被写 (1 次 save), 无源 WIP 扣减, 无 lineage 边
        verify(wipRepo, times(1)).save(any(SemiFinishedInventory.class));
        verify(lineageEdgeRepo, never()).save(any(BatchLineageEdge.class));
        assertThat(wipCap.getValue().getIntermediateBatchNo()).isEqualTo("PT-LU-B1-S1-106");
    }

    @Test
    void submitReport_zeroOutput_doesNotCreateWip() {
        // 产出为 0 (例如只领料不产出的中间记录) → 不建 WIP 行
        WorkProcessTask t = task(107L, 1, "WP-P8");
        t.setProductTypeId("PT-LU");
        setupWipSubmitTask(t);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(107L);
        req.setInputQuantity(new BigDecimal("100"));
        req.setInputUnit("kg");
        req.setOutputQuantity(BigDecimal.ZERO);
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
    }

    // ── G7 getLimits 返回 wipAvailable ────────────────────────────────────────────

    @Test
    void getLimits_secondProcess_returnsWipAvailableFromPrevProcess() {
        // 道2 getLimits: 上道 (processOrder=1) 有 WIP available=400 → wipAvailable=400
        WorkProcessTask t = task(110L, 2, "WP-L1");
        when(taskRepo.findByFactoryIdAndId("F006", 110L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-L1").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-L1")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 110L)).thenReturn(List.of());
        // 上道 WIP (processOrder=1) available=400; 本道 (processOrder=2) WIP 忽略
        SemiFinishedInventory prevWip = SemiFinishedInventory.builder()
                .processOrder(1).availableQuantity(new BigDecimal("400"))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        SemiFinishedInventory thisWip = SemiFinishedInventory.builder()
                .processOrder(2).availableQuantity(new BigDecimal("999"))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 1L))
                .thenReturn(List.of(prevWip, thisWip));

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 110L, new BigDecimal("400"));

        assertThat(dto.getWipAvailable()).isEqualByComparingTo("400");
    }

    @Test
    void getLimits_firstProcess_wipAvailableNull() {
        // 首道 (processOrder=1): 无上道 WIP → wipAvailable=null (领原料不受约束)
        WorkProcessTask t = task(111L, 1, "WP-L2");
        when(taskRepo.findByFactoryIdAndId("F006", 111L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-L2").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-L2")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 111L)).thenReturn(List.of());

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 111L, new BigDecimal("100"));

        assertThat(dto.getWipAvailable()).isNull();
    }

    @Test
    void getLimits_secondProcess_exposesSourceWipUnit() {
        // 道2 getLimits: 上道 WIP 单位 份 → wipAvailableUnit=份 (RN banner/:max 用源 WIP 真实单位)
        WorkProcessTask t = task(113L, 2, "WP-L3");
        when(taskRepo.findByFactoryIdAndId("F006", 113L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-L3").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-L3")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 113L)).thenReturn(List.of());
        // 上道 WIP 单位 份 (与本道 WorkProcess.unit=kg 不同) → wipAvailableUnit 取源 WIP 的 份
        SemiFinishedInventory prevWip = SemiFinishedInventory.builder()
                .processOrder(1).availableQuantity(new BigDecimal("300")).unit("份")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 1L))
                .thenReturn(List.of(prevWip));

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 113L, new BigDecimal("300"));

        assertThat(dto.getWipAvailable()).isEqualByComparingTo("300");
        assertThat(dto.getWipAvailableUnit()).isEqualTo("份");  // 源 WIP 单位, 非本道 unit (kg)
    }

    @Test
    void getLimits_firstProcess_wipAvailableUnitNull() {
        // 首道: 无源 WIP → wipAvailableUnit=null
        WorkProcessTask t = task(114L, 1, "WP-L4");
        when(taskRepo.findByFactoryIdAndId("F006", 114L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-L4").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-L4")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 114L)).thenReturn(List.of());

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 114L, new BigDecimal("100"));

        assertThat(dto.getWipAvailableUnit()).isNull();
    }

    // ── G8 Wave 3 (C): getYield 进行中标注 (在制 WIP + 完工判定) ──────────────────────

    /** 共用: getYield 基础 mock (单道报工, 无 enrich 干扰). */
    private void setupGetYieldBase(Long batchId, ProductionReport... reports) {
        when(reportRepo.findYieldReportsByBatch("F006", batchId)).thenReturn(List.of(reports));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
    }

    @Test
    void getYield_withWipPending_marksInProgress_andSumsWipQuantity() {
        // 批次 IN_PROGRESS + 有在制 WIP 余额 (AVAILABLE 200 + 50) → inProgress=true, wipInProgressQuantity=250
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(120L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("935.5")).outputUnit("kg")
                .build();
        setupGetYieldBase(20L, r1);
        // 批次进行中
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepo.findByIdAndFactoryId(20L, "F006")).thenReturn(Optional.of(batch));
        // 在制 WIP: 两笔 AVAILABLE (200 + 50) + 一笔 DEPLETED (不计) + 一笔 RETURNED (不计)
        SemiFinishedInventory w1 = SemiFinishedInventory.builder()
                .availableQuantity(new BigDecimal("200")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        SemiFinishedInventory w2 = SemiFinishedInventory.builder()
                .availableQuantity(new BigDecimal("50")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        SemiFinishedInventory w3 = SemiFinishedInventory.builder()
                .availableQuantity(BigDecimal.ZERO).unit("kg")
                .status(SemiFinishedInventory.Status.DEPLETED).build();
        SemiFinishedInventory w4 = SemiFinishedInventory.builder()
                .availableQuantity(new BigDecimal("99")).unit("kg")
                .status(SemiFinishedInventory.Status.RETURNED).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 20L))
                .thenReturn(List.of(w1, w2, w3, w4));

        BatchYieldDTO dto = svc.getYield("F006", 20L);

        assertThat(dto.getInProgress()).isTrue();
        assertThat(dto.getWipInProgressQuantity()).isEqualByComparingTo("250");  // 200+50 (DEPLETED/RETURNED 不计)
        assertThat(dto.getWipInProgressUnit()).isEqualTo("kg");
        // cumulativeYieldRate 仍是 A 口径 (未被标注改写); asOf 同源
        assertThat(dto.getAsOfYieldRate()).isEqualByComparingTo(dto.getCumulativeYieldRate());
    }

    @Test
    void getYield_batchCompleted_noWip_marksNotInProgress_wipZero() {
        // 批次 COMPLETED + 无在制 WIP → inProgress=false, wipInProgressQuantity=0, 数字锁定
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(121L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("kg")
                .build();
        setupGetYieldBase(21L, r1);
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(productionBatchRepo.findByIdAndFactoryId(21L, "F006")).thenReturn(Optional.of(batch));
        // 全部 WIP 已领空 (DEPLETED) → 无在制余额
        SemiFinishedInventory depleted = SemiFinishedInventory.builder()
                .availableQuantity(BigDecimal.ZERO).unit("kg")
                .status(SemiFinishedInventory.Status.DEPLETED).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 21L))
                .thenReturn(List.of(depleted));

        BatchYieldDTO dto = svc.getYield("F006", 21L);

        assertThat(dto.getInProgress()).isFalse();
        assertThat(dto.getWipInProgressQuantity()).isEqualByComparingTo("0");
        assertThat(dto.getWipInProgressUnit()).isNull();
    }

    @Test
    void getYield_batchCompletedButWipRemaining_stillInProgress() {
        // 边缘: 批次 COMPLETED 但仍有在制 WIP 结余 → inProgress=true (有未消耗中间品 = 还没真正全部转成品)
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(122L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("kg")
                .build();
        setupGetYieldBase(22L, r1);
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(productionBatchRepo.findByIdAndFactoryId(22L, "F006")).thenReturn(Optional.of(batch));
        SemiFinishedInventory remaining = SemiFinishedInventory.builder()
                .availableQuantity(new BigDecimal("30")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 22L))
                .thenReturn(List.of(remaining));

        BatchYieldDTO dto = svc.getYield("F006", 22L);

        assertThat(dto.getInProgress()).isTrue();
        assertThat(dto.getWipInProgressQuantity()).isEqualByComparingTo("30");
        assertThat(dto.getWipInProgressUnit()).isEqualTo("kg");
    }

    @Test
    void getYield_inProgressBatch_noWipRows_marksInProgress_wipZero() {
        // 批次 IN_PROGRESS 但还没产出 WIP 行 → inProgress=true (未完工), wipInProgressQuantity=0
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(123L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .build();
        setupGetYieldBase(23L, r1);
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepo.findByIdAndFactoryId(23L, "F006")).thenReturn(Optional.of(batch));
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 23L)).thenReturn(List.of());

        BatchYieldDTO dto = svc.getYield("F006", 23L);

        assertThat(dto.getInProgress()).isTrue();
        assertThat(dto.getWipInProgressQuantity()).isEqualByComparingTo("0");
        assertThat(dto.getWipInProgressUnit()).isNull();
    }

    // ── G8 Wave 3 (D3): settleDay 完工时余料退回提示 ──────────────────────────────────

    @Test
    void settleDay_complete_withWipRemaining_addsWipRemainingHint() {
        // 完工成功 + 批次仍有 WIP 结余 (40+10=50kg) → out 含 wipRemainingHint, 不阻塞完工
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(130L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(30L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 30L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepo.findByIdAndFactoryId(30L, "F006")).thenReturn(Optional.of(batch));
        when(processingService.completeProduction(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductionBatch());
        // D3: findRemainingWip 返两笔结余 (40 + 10)
        SemiFinishedInventory w1 = SemiFinishedInventory.builder()
                .availableQuantity(new BigDecimal("40")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        SemiFinishedInventory w2 = SemiFinishedInventory.builder()
                .availableQuantity(new BigDecimal("10")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findRemainingWip("F006", 30L)).thenReturn(List.of(w1, w2));

        Map<String, Object> out = svc.settleDay("F006", 30L, 5L, null, true);

        assertThat(out.get("completed")).isEqualTo(true);
        assertThat(out.get("wipRemaining")).isEqualTo(new BigDecimal("50"));
        assertThat(out.get("wipRemainingUnit")).isEqualTo("kg");
        assertThat(out.get("wipRemainingHint")).asString()
                .contains("50").contains("半成品").contains("退回总仓");
    }

    @Test
    void settleDay_complete_noWipRemaining_noHint() {
        // 完工成功 + 无 WIP 结余 → out 不含 wipRemainingHint
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(131L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("998")).inputUnit("kg")
                .outputQuantity(new BigDecimal("382")).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(31L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 31L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        ProductionBatch batch = new ProductionBatch();
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepo.findByIdAndFactoryId(31L, "F006")).thenReturn(Optional.of(batch));
        when(processingService.completeProduction(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ProductionBatch());
        when(wipRepo.findRemainingWip("F006", 31L)).thenReturn(List.of());

        Map<String, Object> out = svc.settleDay("F006", 31L, 5L, null, true);

        assertThat(out.get("completed")).isEqualTo(true);
        assertThat(out.get("wipRemainingHint")).isNull();
        assertThat(out.get("wipRemaining")).isNull();
    }

    @Test
    void settleDay_notCompleted_noWipRemainingHint_evenIfWipExists() {
        // 未完工 (triggerComplete=false) → 即便有 WIP 结余也不查 findRemainingWip / 不加提示
        ProductionReport r1 = ProductionReport.builder()
                .workProcessTaskId(132L).processOrder(1).productTypeId("PT-LU")
                .inputQuantity(new BigDecimal("100")).inputUnit("kg")
                .outputQuantity(new BigDecimal("80")).outputUnit("kg")
                .build();
        when(reportRepo.findUnsettledYieldReports(eq("F006"), eq(32L), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByBatch("F006", 32L)).thenReturn(List.of(r1));
        when(productTypeRepo.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());
        // 进行中标注会查 batch + wip (getYield 内), 但 D3 hint 不该触发
        when(productionBatchRepo.findByIdAndFactoryId(32L, "F006")).thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 32L)).thenReturn(List.of());

        Map<String, Object> out = svc.settleDay("F006", 32L, 5L, null, false);

        assertThat(out.get("completed")).isEqualTo(false);
        assertThat(out.get("wipRemainingHint")).isNull();
        // D3 findRemainingWip 仅在 completed 时调
        verify(wipRepo, never()).findRemainingWip(anyString(), any());
    }

    // ── G7 Wave 4: getLimits 返回 sourceWipNo (上道唯一可领 WIP 工序批次号) ──────────────

    @Test
    void getLimits_secondProcess_singleAvailablePrevWip_returnsSourceWipNo() {
        // 道2 getLimits: 上道恰有一笔 AVAILABLE WIP (available>0) → sourceWipNo = 其 intermediate_batch_no
        WorkProcessTask t = task(140L, 2, "WP-W1");
        when(taskRepo.findByFactoryIdAndId("F006", 140L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-W1").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-W1")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 140L)).thenReturn(List.of());
        SemiFinishedInventory prevWip = SemiFinishedInventory.builder()
                .intermediateBatchNo("PT-LU-B1-S1-100").processOrder(1)
                .availableQuantity(new BigDecimal("400"))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 1L))
                .thenReturn(List.of(prevWip));

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 140L, new BigDecimal("400"));

        assertThat(dto.getSourceWipNo()).isEqualTo("PT-LU-B1-S1-100");
        assertThat(dto.getWipAvailable()).isEqualByComparingTo("400");
    }

    @Test
    void getLimits_firstProcess_sourceWipNoNull() {
        // 首道: 无源 WIP → sourceWipNo=null
        WorkProcessTask t = task(141L, 1, "WP-W2");
        when(taskRepo.findByFactoryIdAndId("F006", 141L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-W2").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-W2")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 141L)).thenReturn(List.of());

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 141L, new BigDecimal("100"));

        assertThat(dto.getSourceWipNo()).isNull();
    }

    @Test
    void getLimits_multipleAvailablePrevWip_sourceWipNoNull_ambiguous() {
        // 上道有两笔 AVAILABLE WIP (歧义) → sourceWipNo=null (不自动猜, 前端经 GET /wip 选);
        // 但 wipAvailable 仍是两笔之和 (防呆 :max 用总余额)
        WorkProcessTask t = task(142L, 2, "WP-W3");
        when(taskRepo.findByFactoryIdAndId("F006", 142L)).thenReturn(Optional.of(t));
        WorkProcess wp = WorkProcess.builder().id("WP-W3").factoryId("F006")
                .unit("kg").standardYieldMax(new BigDecimal("0.9000")).build();
        when(processRepo.findById("WP-W3")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByTask("F006", 142L)).thenReturn(List.of());
        SemiFinishedInventory wipA = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-A").processOrder(1)
                .availableQuantity(new BigDecimal("100"))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        SemiFinishedInventory wipB = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-B").processOrder(1)
                .availableQuantity(new BigDecimal("50"))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 1L))
                .thenReturn(List.of(wipA, wipB));

        YieldLimitsDTO dto = svc.getLimits("F006", 1L, 142L, new BigDecimal("100"));

        assertThat(dto.getSourceWipNo()).isNull();
        assertThat(dto.getWipAvailable()).isEqualByComparingTo("150");
    }

    // ── G6/G7 Wave 4: listWip (WIP 只读列表) ───────────────────────────────────────────

    @Test
    void listWip_returnsRowsSortedByProcessOrder_withProcessNameJoin() {
        SemiFinishedInventory w2 = SemiFinishedInventory.builder()
                .intermediateBatchNo("PT-LU-B1-S2-201").processOrder(2)
                .sourceWorkProcessTaskId(201L).productTypeId("PT-LU")
                .producedQuantity(new BigDecimal("382")).consumedQuantity(new BigDecimal("0"))
                .availableQuantity(new BigDecimal("382")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        SemiFinishedInventory w1 = SemiFinishedInventory.builder()
                .intermediateBatchNo("PT-LU-B1-S1-200").processOrder(1)
                .sourceWorkProcessTaskId(200L).productTypeId("PT-LU")
                .producedQuantity(new BigDecimal("935.5")).consumedQuantity(new BigDecimal("935.5"))
                .availableQuantity(new BigDecimal("0")).unit("kg")
                .status(SemiFinishedInventory.Status.DEPLETED).build();
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 40L))
                .thenReturn(List.of(w2, w1));  // 故意乱序, 验排序
        WorkProcessTask t1 = task(200L, 1, "WP-A");
        WorkProcessTask t2 = task(201L, 2, "WP-B");
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of(t1, t2));
        when(processRepo.findAllById(any())).thenReturn(List.of(
                WorkProcess.builder().id("WP-A").processName("焯水").build(),
                WorkProcess.builder().id("WP-B").processName("卤制").build()));

        List<WipRowDTO> rows = svc.listWip("F006", 40L);

        assertThat(rows).hasSize(2);
        // processOrder 升序
        assertThat(rows.get(0).getProcessOrder()).isEqualTo(1);
        assertThat(rows.get(0).getIntermediateBatchNo()).isEqualTo("PT-LU-B1-S1-200");
        assertThat(rows.get(0).getProcessName()).isEqualTo("焯水");
        assertThat(rows.get(0).getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
        assertThat(rows.get(1).getProcessOrder()).isEqualTo(2);
        assertThat(rows.get(1).getProcessName()).isEqualTo("卤制");
        assertThat(rows.get(1).getAvailableQuantity()).isEqualByComparingTo("382");
    }

    @Test
    void listWip_noWip_returnsEmptyList() {
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull("F006", 41L)).thenReturn(List.of());

        List<WipRowDTO> rows = svc.listWip("F006", 41L);

        assertThat(rows).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 单元 A.4/A.5: 逐道成本计算 (人工 + 材料) + WIP 成本滚动
    // ══════════════════════════════════════════════════════════════════════════════

    /** 共用 setup: 首道 (task 70, WP-COST), 无历史报工, save 回填 id; report saved 捕获器返回. */
    private ArgumentCaptor<ProductionReport> setupCostTask(WorkProcess wp) {
        WorkProcessTask t = task(70L, 1, "WP-COST");
        t.setProductTypeId("PT-C");
        when(taskRepo.findByFactoryIdAndId("F006", 70L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-COST")).thenReturn(wp == null ? Optional.empty() : Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", 70L)).thenReturn(List.of());
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(700L); return r;
        });
        return cap;
    }

    // ── 人工成本 (test 1, 2) ─────────────────────────────────────────────────────

    @Test
    void submitReport_laborCost_workersTimesHoursTimesRate() {
        // 3 workers × 60 min (=1.0 h) × ¥20/hr = ¥60.00
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setWorkerCount(3); req.setWorkMinutes(60);

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isEqualByComparingTo("60.00");
    }

    @Test
    void submitReport_laborCost_nullWhenRateNull() {
        // standardHourlyRate=null → laborCost null (绝不默认 0)
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(null).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setWorkerCount(3); req.setWorkMinutes(60);

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isNull();
    }

    @Test
    void submitReport_laborCost_nullWhenWorkerCountNull() {
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setWorkerCount(null); req.setWorkMinutes(60);

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isNull();
    }

    @Test
    void submitReport_laborCost_nullWhenWorkMinutesNull() {
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setWorkerCount(3); req.setWorkMinutes(null);

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isNull();
    }

    // ── 材料成本 — 原料领用 (test 3, 5) ─────────────────────────────────────────────

    @Test
    void submitReport_materialCost_rawBatchQtyTimesUnitPrice() {
        // 100kg × ¥10 unitPrice = ¥1000.00
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").build();  // no rate → labor null, isolate material
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-raw-1"); mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setUnitPrice(new BigDecimal("10"));
        when(materialBatchRepo.findById("mb-raw-1")).thenReturn(Optional.of(mb));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-raw-1", new BigDecimal("100"), "kg")));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getMaterialCost()).isEqualByComparingTo("1000.00");
    }

    @Test
    void submitReport_materialCost_nullWhenNoPricesAnywhere() {
        // batch unitPrice null AND no priced WIP → materialCost null (绝不默认 0)
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-noprice"); mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setUnitPrice(null);  // 无单价 (e.g. 仓管员脱敏 或 未录入)
        when(materialBatchRepo.findById("mb-noprice")).thenReturn(Optional.of(mb));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-noprice", new BigDecimal("100"), "kg")));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getMaterialCost()).isNull();
    }

    @Test
    void submitReport_materialCost_someBatchesPricedSomeNot_sumsPriced() {
        // mb-a priced (50×¥10=500), mb-b unpriced → materialCost = 500.00 (non-null, missing treated as 0)
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);
        MaterialBatch mbA = new MaterialBatch();
        mbA.setId("mb-a"); mbA.setStatus(MaterialBatchStatus.AVAILABLE); mbA.setUnitPrice(new BigDecimal("10"));
        MaterialBatch mbB = new MaterialBatch();
        mbB.setId("mb-b"); mbB.setStatus(MaterialBatchStatus.AVAILABLE); mbB.setUnitPrice(null);
        when(materialBatchRepo.findById("mb-a")).thenReturn(Optional.of(mbA));
        when(materialBatchRepo.findById("mb-b")).thenReturn(Optional.of(mbB));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("80")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("70")); req.setOutputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-a", new BigDecimal("50"), "kg"),
                new MaterialBatchRef("mb-b", new BigDecimal("30"), "kg")));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getMaterialCost()).isEqualByComparingTo("500.00");
    }

    // ── 材料成本 — 半成品领用 (test 4) ─────────────────────────────────────────────

    @Test
    void submitReport_materialCost_fromWip_consumedQtyTimesUnitCost() {
        // 领用 80 × wip.unitCost ¥10.6 = ¥848.00 (非首道; sourceWipNo present)
        WorkProcess wp = WorkProcess.builder().id("WP-COST2").factoryId("F006").unit("kg").build();
        WorkProcessTask t = task(71L, 2, "WP-COST2");  // 非首道
        t.setProductTypeId("PT-C");
        when(taskRepo.findByFactoryIdAndId("F006", 71L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-COST2")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask("F006", 71L)).thenReturn(List.of());
        ArgumentCaptor<ProductionReport> cap = ArgumentCaptor.forClass(ProductionReport.class);
        when(reportRepo.save(cap.capture())).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(710L); return r;
        });

        SemiFinishedInventory sourceWip = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-SRC-1").batchId(1L).factoryId("F006")
                .processOrder(1).producedQuantity(new BigDecimal("100"))
                .consumedQuantity(new BigDecimal("0")).availableQuantity(new BigDecimal("100"))
                .unitCost(new BigDecimal("10.6000")).unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("WIP-SRC-1"))
                .thenReturn(Optional.of(sourceWip));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(71L);
        req.setInputQuantity(new BigDecimal("80")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("70")); req.setOutputUnit("kg");
        req.setSourceWipNo("WIP-SRC-1");

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getMaterialCost()).isEqualByComparingTo("848.00");
    }

    // ── WIP 成本滚动 (test 6, 7) ───────────────────────────────────────────────────

    @Test
    void submitReport_wipRollup_accumulatedCostAndUnitCost() {
        // step 产 100 output, laborCost 60 (3×1h×¥20) + materialCost 1000 (100×¥10)
        //   → wip.accumulatedCost=1060, unitCost = 1060/100 = 10.6000
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        setupCostTask(wp);  // first report for task → new WIP row
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-r"); mb.setStatus(MaterialBatchStatus.AVAILABLE); mb.setUnitPrice(new BigDecimal("10"));
        when(materialBatchRepo.findById("mb-r")).thenReturn(Optional.of(mb));

        ArgumentCaptor<SemiFinishedInventory> wipCap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        when(wipRepo.save(wipCap.capture())).thenAnswer(i -> i.getArgument(0));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("100")); req.setOutputUnit("kg");
        req.setWorkerCount(3); req.setWorkMinutes(60);
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-r", new BigDecimal("100"), "kg")));

        svc.submitReport("F006", 1L, 5L, req);

        // last save to wipRepo is the produced WIP upsert
        SemiFinishedInventory wip = wipCap.getValue();
        assertThat(wip.getAccumulatedCost()).isEqualByComparingTo("1060.00");
        assertThat(wip.getUnitCost()).isEqualByComparingTo("10.6000");
    }

    @Test
    void submitReport_wipRollup_crossDay_accumulatesCost() {
        // second produce of +50 with cost +500 over an existing WIP (produced 100, accumulatedCost 1060)
        //   → produced 150, accumulatedCost 1560, unitCost = 1560/150 = 10.4000
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("0")).build();  // labor 0 → isolate material
        WorkProcessTask t = task(70L, 1, "WP-COST");
        t.setProductTypeId("PT-C");
        when(taskRepo.findByFactoryIdAndId("F006", 70L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-COST")).thenReturn(Optional.of(wp));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
        // already 1 report → not first → reuses existing WIP row
        when(reportRepo.findYieldReportsByTask("F006", 70L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("100")).build()));
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0); r.setId(701L); return r;
        });
        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-r"); mb.setStatus(MaterialBatchStatus.AVAILABLE); mb.setUnitPrice(new BigDecimal("10"));
        when(materialBatchRepo.findById("mb-r")).thenReturn(Optional.of(mb));

        // existing WIP row (from day 1): produced 100, accumulatedCost 1060
        SemiFinishedInventory existing = SemiFinishedInventory.builder()
                .intermediateBatchNo("PT-C-B1-S1-70").batchId(1L).factoryId("F006")
                .processOrder(1).producedQuantity(new BigDecimal("100"))
                .consumedQuantity(new BigDecimal("0")).availableQuantity(new BigDecimal("100"))
                .accumulatedCost(new BigDecimal("1060.00")).unitCost(new BigDecimal("10.6000"))
                .unit("kg").status(SemiFinishedInventory.Status.AVAILABLE).build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("PT-C-B1-S1-70"))
                .thenReturn(Optional.of(existing));
        ArgumentCaptor<SemiFinishedInventory> wipCap = ArgumentCaptor.forClass(SemiFinishedInventory.class);
        when(wipRepo.save(wipCap.capture())).thenAnswer(i -> i.getArgument(0));

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("50")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("50")); req.setOutputUnit("kg");
        req.setWorkerCount(3); req.setWorkMinutes(60);  // labor 0 (rate 0)
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-r", new BigDecimal("50"), "kg")));

        svc.submitReport("F006", 1L, 5L, req);

        SemiFinishedInventory wip = wipCap.getValue();
        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("150");
        assertThat(wip.getAccumulatedCost()).isEqualByComparingTo("1560.00");
        assertThat(wip.getUnitCost()).isEqualByComparingTo("10.4000");
    }

    // ── 单元 F (F006 REQ-21): getOrderYieldSummary 分订单出成率聚合 ──────────────────

    /** 构造一条单工序 same-unit YIELD 报工 (input/output 同单位 → cumulative 可算). */
    private ProductionReport batchReport(long batchId, long taskId, BigDecimal in, BigDecimal out, String unit) {
        return ProductionReport.builder()
                .batchId(batchId)
                .workProcessTaskId(taskId).processOrder(1).productTypeId("PT-ORD")
                .inputQuantity(in).inputUnit(unit)
                .outputQuantity(out).outputUnit(unit)
                .build();
    }

    private ProductionBatch batch(long id, String planId) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id); b.setFactoryId("F006"); b.setProductionPlanId(planId);
        b.setStatus(ProductionBatchStatus.IN_PROGRESS);
        return b;
    }

    private com.cretas.aims.entity.ProductionPlan plan(String id) {
        com.cretas.aims.entity.ProductionPlan p = new com.cretas.aims.entity.ProductionPlan();
        p.setId(id); p.setFactoryId("F006");
        return p;
    }

    @Test
    void getOrderYieldSummary_sameUnit_aggregatesTotalsAndOverallRate() {
        // order O-1 → plan PL-1 → 2 batches (101, 102) both kg
        // batch 101: in 100 → out 90 (kg)
        // batch 102: in 200 → out 150 (kg)
        // totalFirstInput=300, totalLastOutput=240, overall=240/300=0.8000
        when(productionPlanRepo.findByFactoryIdAndSourceOrderId("F006", "O-1"))
                .thenReturn(List.of(plan("PL-1")));
        when(productionBatchRepo.findByFactoryIdAndProductionPlanIdIn(eq("F006"), any()))
                .thenReturn(List.of(batch(101L, "PL-1"), batch(102L, "PL-1")));
        when(reportRepo.findYieldReportsByBatch("F006", 101L))
                .thenReturn(List.of(batchReport(101L, 1L, new BigDecimal("100"), new BigDecimal("90"), "kg")));
        when(reportRepo.findYieldReportsByBatch("F006", 102L))
                .thenReturn(List.of(batchReport(102L, 2L, new BigDecimal("200"), new BigDecimal("150"), "kg")));
        // getYield internals: no cross-unit grams lookup needed (same unit), enrich no-op
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        OrderYieldSummaryDTO dto = svc.getOrderYieldSummary("F006", "O-1");

        assertThat(dto.getOrderId()).isEqualTo("O-1");
        assertThat(dto.getBatchCount()).isEqualTo(2);
        assertThat(dto.getBatches()).hasSize(2);
        assertThat(dto.getFirstInputUnit()).isEqualTo("kg");
        assertThat(dto.getLastOutputUnit()).isEqualTo("kg");
        assertThat(dto.getTotalFirstInput()).isEqualByComparingTo("300");
        assertThat(dto.getTotalLastOutput()).isEqualByComparingTo("240");
        assertThat(dto.getOverallYieldRate()).isEqualByComparingTo("0.8000");
    }

    @Test
    void getOrderYieldSummary_differentUnits_totalsAndOverallNull_butBatchesPresent() {
        // batch 201: kg/kg; batch 202: 份/份 → units not comparable → totals + overall null
        when(productionPlanRepo.findByFactoryIdAndSourceOrderId("F006", "O-2"))
                .thenReturn(List.of(plan("PL-2")));
        when(productionBatchRepo.findByFactoryIdAndProductionPlanIdIn(eq("F006"), any()))
                .thenReturn(List.of(batch(201L, "PL-2"), batch(202L, "PL-2")));
        when(reportRepo.findYieldReportsByBatch("F006", 201L))
                .thenReturn(List.of(batchReport(201L, 1L, new BigDecimal("100"), new BigDecimal("90"), "kg")));
        when(reportRepo.findYieldReportsByBatch("F006", 202L))
                .thenReturn(List.of(batchReport(202L, 2L, new BigDecimal("50"), new BigDecimal("48"), "份")));
        when(taskRepo.findByFactoryIdAndIdIn(eq("F006"), any())).thenReturn(List.of());
        when(processRepo.findAllById(any())).thenReturn(List.of());

        OrderYieldSummaryDTO dto = svc.getOrderYieldSummary("F006", "O-2");

        assertThat(dto.getBatchCount()).isEqualTo(2);
        assertThat(dto.getBatches()).hasSize(2);   // individual batches still present
        assertThat(dto.getTotalFirstInput()).isNull();
        assertThat(dto.getTotalLastOutput()).isNull();
        assertThat(dto.getOverallYieldRate()).isNull();
        assertThat(dto.getFirstInputUnit()).isNull();
        assertThat(dto.getLastOutputUnit()).isNull();
    }

    @Test
    void getOrderYieldSummary_noBatches_returnsEmptySummary() {
        // order O-3 → plan exists but no batches (or no plan) → honest empty, NOT exception
        when(productionPlanRepo.findByFactoryIdAndSourceOrderId("F006", "O-3"))
                .thenReturn(List.of(plan("PL-3")));
        when(productionBatchRepo.findByFactoryIdAndProductionPlanIdIn(eq("F006"), any()))
                .thenReturn(List.of());

        OrderYieldSummaryDTO dto = svc.getOrderYieldSummary("F006", "O-3");

        assertThat(dto.getOrderId()).isEqualTo("O-3");
        assertThat(dto.getBatchCount()).isEqualTo(0);
        assertThat(dto.getBatches()).isEmpty();
        assertThat(dto.getTotalFirstInput()).isNull();
        assertThat(dto.getTotalLastOutput()).isNull();
        assertThat(dto.getOverallYieldRate()).isNull();
        assertThat(dto.getTotalCost()).isNull();
        // never queried reports for any batch
        verify(reportRepo, never()).findYieldReportsByBatch(eq("F006"), any());
    }

    @Test
    void getOrderYieldSummary_noPlans_returnsEmptySummary() {
        // order with no production plans at all → empty (no batch lookup)
        when(productionPlanRepo.findByFactoryIdAndSourceOrderId("F006", "O-NONE"))
                .thenReturn(List.of());

        OrderYieldSummaryDTO dto = svc.getOrderYieldSummary("F006", "O-NONE");

        assertThat(dto.getBatchCount()).isEqualTo(0);
        assertThat(dto.getBatches()).isEmpty();
        assertThat(dto.getOverallYieldRate()).isNull();
        verify(productionBatchRepo, never()).findByFactoryIdAndProductionPlanIdIn(any(), any());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 适配单元3: 多段工时 person-hours + 守恒软校验 + 证据/副产物/损耗/留样存储 (修 M2)
    // ══════════════════════════════════════════════════════════════════════════════

    private YieldReportRequest.LaborSegment seg(String start, String end, Integer headcount) {
        YieldReportRequest.LaborSegment s = new YieldReportRequest.LaborSegment();
        s.setStartTime(start); s.setEndTime(end); s.setHeadcount(headcount);
        return s;
    }

    // ── A. 多段工时 person-hours ───────────────────────────────────────────────────

    @Test
    void submitReport_multiSegmentLabor_computesPersonHours() {
        // segs [(08:00-14:30, 12人), (14:30-15:30, 9人)] rate ¥20
        // personMin = 390×12 + 60×9 = 4680 + 540 = 5220 → /60 = 87h × 20 = ¥1740.00
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setLaborSegments(List.of(seg("08:00", "14:30", 12), seg("14:30", "15:30", 9)));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isEqualByComparingTo("1740.00");
    }

    @Test
    void submitReport_multiSegmentLabor_totalMinutesSum_totalWorkersMax() {
        // 工时合计 = Σ segmentMinutes = 390 + 60 = 450; 人数 = MAX headcount = 12 (非 SUM 21) — 修 M2
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setLaborSegments(List.of(seg("08:00", "14:30", 12), seg("14:30", "15:30", 9)));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getTotalWorkMinutes()).isEqualTo(450);
        assertThat(cap.getValue().getTotalWorkers()).isEqualTo(12);   // MAX peak, 不是 SUM 21
    }

    @Test
    void submitReport_crossMidnightSegment_addsFullDay() {
        // 跨夜 (22:00-01:00, 2人): end < start → +1440 → 3h = 180min × 2 = 360 person-min
        // rate ¥10 → 360/60 = 6h × 10 = ¥60.00; totalWorkMinutes = 180
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("10.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setLaborSegments(List.of(seg("22:00", "01:00", 2)));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getTotalWorkMinutes()).isEqualTo(180);
        assertThat(cap.getValue().getLaborCost()).isEqualByComparingTo("60.00");
    }

    @Test
    void submitReport_noLaborSegments_fallsBackToSingleWorkerCount() {
        // laborSegments null → 退回单一 workerCount/workMinutes 旧路径 (back-compat)
        // 3 workers × 60min (1h) × ¥20 = ¥60.00; totalWorkers=3 (单一), totalWorkMinutes=60
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(new BigDecimal("20.00")).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setWorkerCount(3); req.setWorkMinutes(60);   // 无 laborSegments

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isEqualByComparingTo("60.00");
        assertThat(cap.getValue().getTotalWorkers()).isEqualTo(3);
        assertThat(cap.getValue().getTotalWorkMinutes()).isEqualTo(60);
    }

    @Test
    void submitReport_laborSegments_nullRate_laborCostNull() {
        // 多段工时但 rate null → laborCost null (绝不默认 0); 工时/人数仍从 segs 派生
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006")
                .unit("kg").standardHourlyRate(null).build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setLaborSegments(List.of(seg("08:00", "14:30", 12), seg("14:30", "15:30", 9)));

        svc.submitReport("F006", 1L, 5L, req);

        assertThat(cap.getValue().getLaborCost()).isNull();
        assertThat(cap.getValue().getTotalWorkMinutes()).isEqualTo(450);
        assertThat(cap.getValue().getTotalWorkers()).isEqualTo(12);
    }

    // ── B. 守恒软校验 (balanceWarning) ─────────────────────────────────────────────

    @Test
    void submitReport_balanceWarning_whenDeviationOver15pct() {
        // input 100, output 60, byproduct 10, waste 5 → balance 25, 25/100 = 25% > 15% → 告警
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("60")); req.setOutputUnit("kg");
        req.setByproducts(List.of(byproduct("料头", "10", "kg")));
        req.setWasteQuantity(new BigDecimal("5"));

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("balanceWarning")).isNotNull();
        assertThat(out.get("balanceWarning").toString()).contains("物料平衡偏差");
    }

    @Test
    void submitReport_noBalanceWarning_whenBalanced() {
        // input 100, output 90, byproduct 5, waste 5 → balance 0 → 无告警
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setByproducts(List.of(byproduct("料头", "5", "kg")));
        req.setWasteQuantity(new BigDecimal("5"));

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("balanceWarning")).isNull();
    }

    @Test
    void submitReport_noBalanceWarning_whenUnitsDiffer() {
        // input kg, output 份 (跨单位) → 守恒不可比 → 无告警 (即便数值偏差大)
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("10")); req.setOutputUnit("份");   // 跨单位

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("balanceWarning")).isNull();
    }

    @Test
    void submitReport_noBalanceWarning_whenInputNull() {
        // inputQuantity null → 无基准 → 无告警
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(null); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("10")); req.setOutputUnit("kg");

        Map<String, Object> out = svc.submitReport("F006", 1L, 5L, req);

        assertThat(out.get("balanceWarning")).isNull();
    }

    // ── C. 证据/副产物/损耗/留样 存储 ───────────────────────────────────────────────

    @Test
    void submitReport_persistsEvidenceByproductsWasteSample() {
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");
        req.setEvidenceImages(List.of("https://oss/img1.jpg", "https://oss/img2.jpg"));
        req.setByproducts(List.of(byproduct("料头", "10", "kg"), byproduct("肥油", "3", "kg")));
        req.setWasteQuantity(new BigDecimal("5"));
        req.setSampleRetainQuantity(4);

        svc.submitReport("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getPhotos()).containsExactly("https://oss/img1.jpg", "https://oss/img2.jpg");
        assertThat(saved.getByproducts()).isNotNull().hasSize(2);
        assertThat(saved.getByproducts().get(0).get("name")).isEqualTo("料头");
        assertThat(saved.getByproducts().get(0).get("quantity")).isEqualTo(new BigDecimal("10"));
        assertThat(saved.getByproducts().get(0).get("unit")).isEqualTo("kg");
        assertThat(saved.getWasteQuantity()).isEqualByComparingTo("5");
        assertThat(saved.getSampleRetainQuantity()).isEqualTo(4);
    }

    @Test
    void submitReport_noTraditionalFields_persistsNull() {
        // 不传证据/副产物/损耗/留样 → 字段保持 null (back-compat)
        WorkProcess wp = WorkProcess.builder().id("WP-COST").factoryId("F006").unit("kg").build();
        ArgumentCaptor<ProductionReport> cap = setupCostTask(wp);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(70L);
        req.setInputQuantity(new BigDecimal("100")); req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("90")); req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        ProductionReport saved = cap.getValue();
        assertThat(saved.getPhotos()).isNull();
        assertThat(saved.getByproducts()).isNull();
        assertThat(saved.getWasteQuantity()).isNull();
        assertThat(saved.getSampleRetainQuantity()).isNull();
    }

    private YieldReportRequest.Byproduct byproduct(String name, String qty, String unit) {
        YieldReportRequest.Byproduct b = new YieldReportRequest.Byproduct();
        b.setName(name); b.setQuantity(new BigDecimal(qty)); b.setUnit(unit);
        return b;
    }
}
