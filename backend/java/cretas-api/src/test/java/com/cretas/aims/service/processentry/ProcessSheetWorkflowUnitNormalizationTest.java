package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 2B (clerk-path workflow 联通) — {@code applyWorkflowConfiguredUnits} 单位归一化单测。
 *
 * <p>workflow 计划的产品(尤其成品)没有 legacy ProductWorkProcess 配置。这条路径让报工单位以
 * workflow 端口投影为准 —— 修复"成品包装 kg(半成品)→盒(成品) 换算行被 PROCESS_SHEET_PROCESS_NOT_CONFIGURED
 * 400 拒绝"的 E2E 缺口。
 *
 * <ol>
 *   <li>成品换算行 (input kg / output 盒) → 用端口单位归一化, 返回 true, 不抛。</li>
 *   <li>legacy 计划 (config null) → 返回 false, 交回 legacy 分支。</li>
 *   <li>半成品单单位行 (kg→kg) → 与 legacy 单位一致, 返回 true。</li>
 *   <li>请求单位与端口不符 → 409 PROCESS_SHEET_UNIT_MISMATCH。</li>
 *   <li>该 processOrder 无描述符 → 返回 false, 交回 legacy 分支。</li>
 * </ol>
 */
class ProcessSheetWorkflowUnitNormalizationTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PLAN-WF-UNIT-001";

    private ProcessSheetServiceImpl newImpl(WorkflowClerkSheetService svc) throws Exception {
        ProcessSheetServiceImpl impl = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Field f = ProcessSheetServiceImpl.class.getDeclaredField("workflowClerkSheetService");
        f.setAccessible(true);
        f.set(impl, svc);
        return impl;
    }

    private boolean apply(ProcessSheetServiceImpl impl, ProcessSheetRowRequest req) throws Throwable {
        Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "applyWorkflowConfiguredUnits", String.class, String.class, ProcessSheetRowRequest.class);
        m.setAccessible(true);
        try {
            return (boolean) m.invoke(impl, FACTORY_ID, PLAN_ID, req);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void normalize(ProcessSheetServiceImpl impl, ProcessSheetRowRequest req) throws Throwable {
        Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "normalizeConfiguredUnits", String.class, String.class, ProcessSheetRowRequest.class);
        m.setAccessible(true);
        try {
            m.invoke(impl, FACTORY_ID, PLAN_ID, req);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private BigDecimal toStorageQuantity(
            BigDecimal reportingQuantity, String reportingUnit, String storageUnit) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "convertReportingQuantityToStorage",
                BigDecimal.class, String.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return (BigDecimal) method.invoke(
                    null, reportingQuantity, reportingUnit, storageUnit, "原料批次");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private ProcessSheetRowRequest row(int processOrder, String unit, String inputUnit, String outputUnit) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setProcessOrder(processOrder);
        req.setUnit(unit);
        req.setInputUnit(inputUnit);
        req.setOutputUnit(outputUnit);
        req.setOutputQuantity(new BigDecimal("10"));
        return req;
    }

    private WorkflowClerkSheetConfigDTO config(int processOrder, boolean outFinished,
                                               String inUnit, String outUnit) {
        PortDescriptor input = PortDescriptor.builder()
                .workflowPortId("in-" + processOrder)
                .materialKind("SEMI_FINISHED")
                .skuId("PT-in-" + processOrder)
                .materialName("半成品投料")
                .unit(inUnit)
                .required(true)
                .skuResolved(true)
                .finished(false)
                .build();
        PortDescriptor output = PortDescriptor.builder()
                .workflowPortId("out-" + processOrder)
                .materialKind(outFinished ? "FINISHED_GOOD" : "SEMI_FINISHED")
                .skuId("PT-out-" + processOrder)
                .materialName(outFinished ? "成品" : "半成品")
                .unit(outUnit)
                .gramsPerUnit(outFinished ? new BigDecimal("200") : null)
                .required(true)
                .skuResolved(true)
                .finished(outFinished)
                .build();
        ProcessDescriptor desc = ProcessDescriptor.builder()
                .workflowNodeId("node-" + processOrder)
                .workProcessId("WP-" + processOrder)
                .processName("工序" + processOrder)
                .processOrder(processOrder)
                .plannedUnit(outUnit)
                .inputs(inUnit == null ? List.of() : List.of(input))
                .output(output)
                .build();
        return WorkflowClerkSheetConfigDTO.builder()
                .workflowBatchId(901L)
                .workflowInstanceId(11L)
                .productTypeId("PT-product")
                .processes(List.of(desc))
                .build();
    }

    @Test
    @DisplayName("成品换算行 (input kg → output 盒) → 端口单位归一化, 不抛 PROCESS_NOT_CONFIGURED")
    void finishedConversionRow_normalizesFromPorts() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        WorkflowClerkSheetConfigDTO workflow = config(3, true, "kg", "盒");
        workflow.getProcesses().getFirst().getOutput().setGramsPerUnit(new BigDecimal("200"));
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(workflow);
        ProcessSheetServiceImpl impl = newImpl(svc);

        ProcessSheetRowRequest req = row(3, "盒", "kg", "盒");
        assertTrue(apply(impl, req));
        assertEquals("kg", req.getInputUnit());
        assertEquals("盒", req.getOutputUnit());
        assertEquals("盒", req.getUnit());
        assertEquals(0, new BigDecimal("2").compareTo(req.getProductWeight()));
    }

    @Test
    @DisplayName("Workflow canonical box 接受客户端显示别名盒并归一化")
    void finishedConversionRow_acceptsChineseAliasForCanonicalBox() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        WorkflowClerkSheetConfigDTO workflow = config(3, true, "kg", "box");
        workflow.getProcesses().getFirst().getOutput().setGramsPerUnit(new BigDecimal("800"));
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(workflow);
        ProcessSheetServiceImpl impl = newImpl(svc);

        ProcessSheetRowRequest req = row(3, "盒", "kg", "盒");
        assertTrue(apply(impl, req));
        assertEquals("kg", req.getInputUnit());
        assertEquals("box", req.getOutputUnit());
        assertEquals("box", req.getUnit());
        assertEquals(0, new BigDecimal("8").compareTo(req.getProductWeight()));
    }

    @Test
    @DisplayName("计数型成品缺少净重快照时阻止报工")
    void finishedCountOutputRequiresNetWeightSnapshot() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        WorkflowClerkSheetConfigDTO workflow = config(3, true, "kg", "盒");
        workflow.getProcesses().getFirst().getOutput().setGramsPerUnit(null);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(workflow);
        ProcessSheetServiceImpl impl = newImpl(svc);

        ProcessSheetRowRequest req = row(3, "盒", "kg", "盒");
        assertThatThrownBy(() -> apply(impl, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertEquals("FINISHED_SKU_NET_WEIGHT_SNAPSHOT_MISSING", error.getErrorCode()));
    }

    @Test
    @DisplayName("Workflow 与 legacy 同时存在时只认 Workflow 端口单位")
    void workflowPortsWinEvenWhenLegacyProcessExists() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(2, true, "g", "件"));

        ProductWorkProcessRepository legacyRepo = mock(ProductWorkProcessRepository.class);
        WorkProcessRepository workProcessRepo = mock(WorkProcessRepository.class);
        ProcessSheetServiceImpl impl = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, workProcessRepo, legacyRepo, null, null, null, null);
        Field workflowField = ProcessSheetServiceImpl.class.getDeclaredField("workflowClerkSheetService");
        workflowField.setAccessible(true);
        workflowField.set(impl, svc);

        ProcessSheetRowRequest req = row(2, "件", "g", "件");
        req.setProductTypeId("PT-product");
        normalize(impl, req);

        assertEquals("g", req.getInputUnit());
        assertEquals("件", req.getOutputUnit());
        assertEquals("件", req.getUnit());
        verifyNoInteractions(legacyRepo, workProcessRepo);
    }

    @Test
    @DisplayName("legacy 成品重量由计划快照覆盖，忽略客户端伪造值")
    void legacyFinishedWeightComesFromPlanSnapshot() throws Throwable {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductWorkProcessRepository legacyRepo = mock(ProductWorkProcessRepository.class);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId("PT-product");
        plan.setPlannedUnit("盒");
        plan.setPlannedNetWeightGrams(new BigDecimal("200"));
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(java.util.Optional.of(plan));
        when(legacyRepo.findByFactoryIdAndProductTypeIdAndProcessOrder(
                FACTORY_ID, "PT-product", 3)).thenReturn(java.util.Optional.empty());

        ProcessSheetServiceImpl impl = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, planRepo, null,
                null, null, null, null, legacyRepo, null, null, null, null);
        ProcessSheetRowRequest req = row(3, "盒", null, null);
        req.setProductTypeId("PT-product");
        req.setFinished(true);
        req.setOutputQuantity(new BigDecimal("50"));
        req.setProductWeight(new BigDecimal("999"));

        normalize(impl, req);

        assertEquals(0, new BigDecimal("10").compareTo(req.getProductWeight()));
    }

    @Test
    @DisplayName("legacy 计划 (config null) → 返回 false, 交回 legacy 分支")
    void legacyPlan_returnsFalse() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(null);
        ProcessSheetServiceImpl impl = newImpl(svc);
        assertFalse(apply(impl, row(1, "kg", null, null)));
    }

    @Test
    @DisplayName("半成品单单位行 (kg→kg) → 与 legacy 单位一致, 返回 true")
    void semiSingleUnitRow_normalizesToKg() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(1, false, "kg", "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);

        ProcessSheetRowRequest req = row(1, "kg", null, null);
        assertTrue(apply(impl, req));
        assertEquals("kg", req.getInputUnit());
        assertEquals("kg", req.getOutputUnit());
        assertEquals("kg", req.getUnit());
    }

    @Test
    @DisplayName("Workflow 工序缺少投入端口 → 阻断, 不用产出单位猜测投入单位")
    void missingInputPort_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(3, true, null, "件"));
        ProcessSheetServiceImpl impl = newImpl(svc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> apply(impl, row(3, "件", null, "件")));
        assertEquals("PROCESS_SHEET_WORKFLOW_INPUT_PORT_MISSING", ex.getErrorCode());
    }

    @Test
    @DisplayName("请求产出单位与端口不符 (端口=盒, 请求=只) → 409 UNIT_MISMATCH")
    void outputUnitMismatch_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(3, true, "kg", "盒"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> apply(impl, row(3, "只", "kg", "只")));
        assertEquals("PROCESS_SHEET_UNIT_MISMATCH", ex.getErrorCode());
    }

    @Test
    @DisplayName("Workflow 中缺少本道工序 → 明确阻断, 不回退 legacy")
    void processOrderNotInWorkflow_throwsWithoutLegacyFallback() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(1, false, "kg", "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> apply(impl, row(9, "盒", "kg", "盒")));
        assertEquals("PROCESS_SHEET_WORKFLOW_PROCESS_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("Workflow 多产出工序不能使用单产出请求绕过端口校验")
    void multiOutputWorkflow_rejectsSingleOutputRequest() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        WorkflowClerkSheetConfigDTO workflow = config(4, true, "g", "件");
        ProcessDescriptor descriptor = workflow.getProcesses().get(0);
        PortDescriptor secondary = PortDescriptor.builder()
                .workflowPortId("out-secondary")
                .materialKind("SEMI_FINISHED")
                .skuId("PT-secondary")
                .materialName("副产物")
                .unit("g")
                .required(false)
                .skuResolved(true)
                .finished(false)
                .build();
        descriptor.setOutputs(List.of(descriptor.getOutput(), secondary));
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(workflow);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> apply(newImpl(svc), row(4, "件", "g", "件")));
        assertEquals("PROCESS_SHEET_WORKFLOW_MULTI_OUTPUT_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Workflow 配置读取异常 → 明确阻断, 不回退 legacy")
    void workflowLookupFailure_throwsWithoutLegacyFallback() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID))
                .thenThrow(new IllegalStateException("runtime snapshot unavailable"));
        ProcessSheetServiceImpl impl = newImpl(svc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> apply(impl, row(1, "kg", "kg", "kg")));
        assertEquals("PROCESS_SHEET_WORKFLOW_CONFIG_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    void kgReportingQuantityConvertsToLegacyGramStorageAtDeductionBoundary() throws Throwable {
        assertEquals(0, new BigDecimal("1250").compareTo(
                toStorageQuantity(new BigDecimal("1.25"), "kg", "g")));
    }

    @Test
    void gramReportingQuantityConvertsToKgStorageAtDeductionBoundary() throws Throwable {
        assertEquals(0, new BigDecimal("1.25").compareTo(
                toStorageQuantity(new BigDecimal("1250"), "g", "kg")));
    }

    @Test
    void nonMassStorageMismatchStillFailsClosed() {
        assertThatThrownBy(() -> toStorageQuantity(new BigDecimal("2"), "kg", "盒"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertEquals("PROCESS_SHEET_SOURCE_UNIT_MISMATCH", error.getErrorCode()));
    }

    @Test
    void missingStorageUnitFailsClosed() {
        assertThatThrownBy(() -> toStorageQuantity(new BigDecimal("2"), "kg", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertEquals("PROCESS_SHEET_SOURCE_UNIT_MISMATCH", error.getErrorCode()));
    }

    @Test
    @DisplayName("多投入单产出按端口固定 g/kg，组级投入统一为 kg")
    void multiInputSingleOutput_usesAuthoritativePortUnits() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        PortDescriptor grams = PortDescriptor.builder()
                .workflowPortId("in-grams").materialNodeId("raw-a")
                .materialKind("RAW_MATERIAL").skuId("RAW-A").unit("g").required(true).build();
        PortDescriptor kilos = PortDescriptor.builder()
                .workflowPortId("in-kilos").materialNodeId("raw-b")
                .materialKind("RAW_MATERIAL").skuId("RAW-B").unit("kg").required(true).build();
        PortDescriptor output = PortDescriptor.builder()
                .workflowPortId("out-kg").materialKind("SEMI_FINISHED")
                .skuId("PT-OUT").unit("kg").required(true).finished(false).build();
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(2).inputs(List.of(grams, kilos)).output(output).outputs(List.of(output)).build();
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(
                WorkflowClerkSheetConfigDTO.builder().processes(List.of(descriptor)).build());

        ProcessSheetRowRequest.MaterialInputTotal first = new ProcessSheetRowRequest.MaterialInputTotal();
        first.setMaterialTypeId("RAW-A");
        first.setWorkflowPortId("in-grams");
        first.setQuantity(new BigDecimal("1000"));
        first.setUnit("g");
        ProcessSheetRowRequest.MaterialInputTotal second = new ProcessSheetRowRequest.MaterialInputTotal();
        second.setMaterialTypeId("RAW-B");
        second.setWorkflowPortId("in-kilos");
        second.setQuantity(new BigDecimal("2"));
        second.setUnit("kg");
        ProcessSheetRowRequest req = row(2, "kg", "kg", "kg");
        req.setMaterialInputTotals(List.of(first, second));

        assertTrue(apply(newImpl(svc), req));
        assertEquals("kg", req.getInputUnit());
        assertEquals("g", first.getUnit());
        assertEquals("kg", second.getUnit());
        assertEquals("raw-a", first.getMaterialNodeId());
        assertEquals("raw-b", second.getMaterialNodeId());
    }

    @Test
    @DisplayName("多投入缺端口身份时明确阻断，不按数组位置猜")
    void multiInputWithoutPortIdentity_failsClosed() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        PortDescriptor firstPort = PortDescriptor.builder()
                .workflowPortId("in-a").materialKind("RAW_MATERIAL")
                .skuId("RAW-A").unit("kg").required(true).build();
        PortDescriptor secondPort = PortDescriptor.builder()
                .workflowPortId("in-b").materialKind("RAW_MATERIAL")
                .skuId("RAW-B").unit("kg").required(true).build();
        PortDescriptor output = PortDescriptor.builder()
                .workflowPortId("out").materialKind("SEMI_FINISHED")
                .skuId("PT-OUT").unit("kg").required(true).finished(false).build();
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(2).inputs(List.of(firstPort, secondPort))
                .output(output).outputs(List.of(output)).build();
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(
                WorkflowClerkSheetConfigDTO.builder().processes(List.of(descriptor)).build());
        ProcessSheetRowRequest.MaterialInputTotal input = new ProcessSheetRowRequest.MaterialInputTotal();
        input.setMaterialTypeId("RAW-A");
        input.setQuantity(BigDecimal.ONE);
        input.setUnit("kg");
        ProcessSheetRowRequest req = row(2, "kg", "kg", "kg");
        req.setMaterialInputTotals(List.of(input));

        BusinessException error = assertThrows(BusinessException.class,
                () -> apply(newImpl(svc), req));
        assertEquals("PROCESS_SHEET_WORKFLOW_INPUT_PORT_REQUIRED", error.getErrorCode());
    }
}
