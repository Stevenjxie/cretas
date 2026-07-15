package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.MaterializeContext;
import com.cretas.aims.dto.processentry.MaterializedBatch;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ResolvedEdge;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessSheetWorkflowRuntimeBatchReuseTest {

    private static final String FACTORY = "F006";
    private static final Long RUNTIME_BATCH_ID = 10553L;

    private final ClerkProcessEntryService clerkService = mock(ClerkProcessEntryService.class);
    private final ProcessSheetRowRepository rowRepository = mock(ProcessSheetRowRepository.class);
    private final ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
    private final ProcessSheetServiceImpl service = new ProcessSheetServiceImpl(
            clerkService, rowRepository, null, batchRepository, null, null, null, null,
            null, null, null, null, null, null, null, null, null);

    @Test
    @DisplayName("成品道复用计划已有 Workflow 运行批次，不创建第二个批次")
    void finishedRowReusesCanonicalWorkflowBatch() throws Throwable {
        ProductionBatch runtimeBatch = runtimeBatch("PT-FG");
        when(batchRepository.findByIdAndFactoryId(RUNTIME_BATCH_ID, FACTORY))
                .thenReturn(Optional.of(runtimeBatch));
        when(rowRepository.findByFactoryIdAndBatchId(FACTORY, RUNTIME_BATCH_ID))
                .thenReturn(List.of());
        when(clerkService.rematerializeInPlace(
                any(), eq(RUNTIME_BATCH_ID), eq(null), any(), any(), any()))
                .thenReturn(new MaterializedBatch(
                        RUNTIME_BATCH_ID, runtimeBatch.getBatchNumber(), null, BigDecimal.TEN, 1));

        MaterializedBatch result = invoke(context(true, "PT-FG"), workflowConfig());

        assertEquals(RUNTIME_BATCH_ID, result.getProductionBatchId());
        verify(clerkService).rematerializeInPlace(
                any(), eq(RUNTIME_BATCH_ID), eq(null), any(), any(), any());
        verify(clerkService, never()).materializeBatch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("运行批次已绑定逐道行时拒绝重复占用")
    void refusesRuntimeBatchAlreadyBoundToAnotherRow() {
        when(batchRepository.findByIdAndFactoryId(RUNTIME_BATCH_ID, FACTORY))
                .thenReturn(Optional.of(runtimeBatch("PT-FG")));
        when(rowRepository.findByFactoryIdAndBatchId(FACTORY, RUNTIME_BATCH_ID))
                .thenReturn(List.of(new ProcessSheetRow()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> invoke(context(true, "PT-FG"), workflowConfig()));

        assertEquals("WORKFLOW_RUNTIME_BATCH_ALREADY_REPORTED", error.getErrorCode());
        verify(clerkService, never()).materializeBatch(any(), any(), any(), any());
        verify(clerkService, never()).rematerializeInPlace(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("legacy 或半成品路径仍按原逻辑创建物化批次")
    void legacyPathKeepsExistingMaterializationBehavior() throws Throwable {
        MaterializedBatch created = new MaterializedBatch(22L, "CLK-W-22", null, null, 0);
        when(clerkService.materializeBatch(any(), any(), any(), any())).thenReturn(created);

        MaterializedBatch result = invoke(context(false, "PT-WIP"), null);

        assertEquals(22L, result.getProductionBatchId());
        verify(clerkService).materializeBatch(any(), any(), any(), any());
    }

    private MaterializedBatch invoke(MaterializeContext context, WorkflowClerkSheetConfigDTO config)
            throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "materializeSheetBatch",
                MaterializeContext.class, List.class, List.class, List.class,
                WorkflowClerkSheetConfigDTO.class);
        method.setAccessible(true);
        try {
            return (MaterializedBatch) method.invoke(
                    service, context, List.of(new StepEntry()), List.<ResolvedEdge>of(),
                    new ArrayList<String>(), config);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private MaterializeContext context(boolean finished, String productTypeId) {
        return new MaterializeContext(
                FACTORY, finished ? "PLAN-WF" : null, productTypeId, null, finished,
                BigDecimal.ONE, "WH-WKS", null, 1309L);
    }

    private WorkflowClerkSheetConfigDTO workflowConfig() {
        return WorkflowClerkSheetConfigDTO.builder()
                .workflowBatchId(RUNTIME_BATCH_ID)
                .workflowInstanceId(31L)
                .processes(List.of())
                .build();
    }

    private ProductionBatch runtimeBatch(String productTypeId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(RUNTIME_BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setProductTypeId(productTypeId);
        batch.setBatchNumber("PB-PLAN-10553");
        return batch;
    }
}
