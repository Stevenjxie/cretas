package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private YieldReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        processingService = mock(ProcessingService.class);
        calcSvc = new YieldCalculationServiceImpl();
        svc = new YieldReportServiceImpl(reportRepo, taskRepo, processRepo, calcSvc, processingService);
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
        // 该任务累计 YIELD 报工: 80 + 70 -> task.actualQuantity 应=150
        when(reportRepo.findYieldReportsByTask("F006", 10L)).thenReturn(List.of(
                ProductionReport.builder().outputQuantity(new BigDecimal("80")).build(),
                ProductionReport.builder().outputQuantity(new BigDecimal("70")).build()
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
}
