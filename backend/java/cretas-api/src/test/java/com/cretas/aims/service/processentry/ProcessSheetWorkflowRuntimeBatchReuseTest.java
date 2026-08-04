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

    /**
     * 🔴 2026-08-04 规则反转：运行批次被占用 → <b>开新批次</b>，不再 409。
     *
     * <p>原来这里断言的是 {@code WORKFLOW_RUNTIME_BATCH_ALREADY_REPORTED}。六膳门实撞出它的后果：
     * 计划 {@code PLAN-1785831853929}（SAFETY_STOCK，计划量 0 = 按实际报工）第一批装箱报完、
     * 小结、入库全部走通，做第二批时成品道保存直接 409 —— 而且四道门互相咬死：先小结再报也拦
     * （守卫不看小结状态）、删掉第一行腾位置也不行（已小结的行禁止删除）、给计划再开一个批次也不行
     * （{@code createBatchFromPlan} 明确「不许建出第二个批次」）。**没有任何界面操作能重置它。**
     *
     * <p>放开是安全的：运行时快照的唯一性由 {@code findWorkflowRuntime} 守，它只数
     * {@code workflowSelectionMode == WORKFLOW} 的批次，而文员通路建的批次不带这个模式
     * （prod 上同一张计划长期并存 1 个 WORKFLOW 批次 + 7 个文员批次，报工页正常）。
     * 下游也早就支持多批：小结按 {@code sessionSeq} 分场次，一次小结把所有未结成品行按产品
     * 聚合成一个成品批次入库，撤销按聚合量精确逆转。
     *
     * <p>客户口径（2026-08-04 张权）：「本来小结前都是类似草稿的，小结了库存才入库的」
     * 「多个批次就小结多次呗，无所谓的」。
     */
    @Test
    @DisplayName("运行批次已被上一批占用时，成品道开自己的新批次(库存生产要能一直生产下去)")
    void secondFinishedRowOpensItsOwnBatch() throws Throwable {
        when(batchRepository.findByIdAndFactoryId(RUNTIME_BATCH_ID, FACTORY))
                .thenReturn(Optional.of(runtimeBatch("PT-FG")));
        when(rowRepository.findByFactoryIdAndBatchId(FACTORY, RUNTIME_BATCH_ID))
                .thenReturn(List.of(new ProcessSheetRow()));
        MaterializedBatch nextRunBatch = new MaterializedBatch(99L, "CLK-W-99", null, null, 0);
        when(clerkService.materializeBatch(any(), any(), any(), any())).thenReturn(nextRunBatch);

        MaterializedBatch result = invoke(context(true, "PT-FG"), workflowConfig());

        assertEquals(99L, result.getProductionBatchId(),
                "第二批应落在自己的新批次上, 而不是被 409 拦下");
        verify(clerkService).materializeBatch(any(), any(), any(), any());
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
                FACTORY, finished ? "PLAN-WF" : null, productTypeId, productTypeId, null, finished,
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
