package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.yield.YieldCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for YieldReportServiceImpl — audit YIELD-4: processName enrichment.
 */
class YieldReportServiceImplTest {

    @Mock
    private ProductionReportRepository reportRepo;

    @Mock
    private WorkProcessTaskRepository taskRepo;

    @Mock
    private WorkProcessRepository processRepo;

    @Mock
    private YieldCalculationService calcSvc;

    @Mock
    private ProcessingService processingService;

    @InjectMocks
    private YieldReportServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getYield_enrichesProcessNamesFromWorkProcess() {
        // --- build BatchYieldDTO with 2 steps (processName unset = null) ---
        StepYieldDTO step1 = StepYieldDTO.builder()
                .workProcessTaskId(24L)
                .processOrder(1)
                .build();
        StepYieldDTO step2 = StepYieldDTO.builder()
                .workProcessTaskId(25L)
                .processOrder(2)
                .build();
        BatchYieldDTO dto = BatchYieldDTO.builder()
                .batchId(1897L)
                .steps(List.of(step1, step2))
                .build();

        when(reportRepo.findYieldReportsByBatch(any(), any())).thenReturn(List.of());
        when(calcSvc.calculateBatchYield(any(), any())).thenReturn(dto);

        // --- WorkProcessTask stubs ---
        WorkProcessTask t1 = new WorkProcessTask();
        t1.setId(24L);
        t1.setWorkProcessId("W1");

        WorkProcessTask t2 = new WorkProcessTask();
        t2.setId(25L);
        t2.setWorkProcessId("W2");

        when(taskRepo.findByFactoryIdAndIdIn(any(), any())).thenReturn(List.of(t1, t2));

        // --- WorkProcess stubs ---
        WorkProcess w1 = WorkProcess.builder().id("W1").factoryId("F001").processName("处理").build();
        WorkProcess w2 = WorkProcess.builder().id("W2").factoryId("F001").processName("滚揉").build();

        when(processRepo.findAllById(any())).thenReturn(List.of(w1, w2));

        // --- execute ---
        BatchYieldDTO result = service.getYield("F001", 1897L);

        // --- assert ---
        assertThat(result.getSteps())
                .extracting(StepYieldDTO::getProcessName)
                .containsExactly("处理", "滚揉");
    }
}
