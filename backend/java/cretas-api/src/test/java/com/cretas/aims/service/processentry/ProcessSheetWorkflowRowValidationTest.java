package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 2B B3 (clerk-path workflow 报工联通) — {@code validateWorkflowRowIfApplicable} 防呆校验单测。
 *
 * <p>验证 workflow 批次行保存前的产出对齐校验 (additive, 禁止降级):
 * <ol>
 *   <li>未注入 service (单测/legacy 环境) → 放行。</li>
 *   <li>service 返回 null (legacy 计划无 workflow 批次) → 放行。</li>
 *   <li>DRAFT 行 (outputQuantity &lt;= 0) → 跳过 (尚无产出)。</li>
 *   <li>产出类型 半成品/成品 不符 → 409 WORKFLOW_ROW_OUTPUT_KIND_MISMATCH。</li>
 *   <li>产出单位 不符 → 409 WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH。</li>
 *   <li>类型+单位对齐 → 放行。</li>
 *   <li>该 processOrder 不在 workflow 图中 → 409，不允许回退 legacy。</li>
 *   <li>service 抛 BusinessException (如多产出守卫) → 原样 surface。</li>
 * </ol>
 */
class ProcessSheetWorkflowRowValidationTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PLAN-WF-001";

    private ProcessSheetServiceImpl newImpl(WorkflowClerkSheetService svc) throws Exception {
        ProcessSheetServiceImpl impl = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Field f = ProcessSheetServiceImpl.class.getDeclaredField("workflowClerkSheetService");
        f.setAccessible(true);
        f.set(impl, svc);
        return impl;
    }

    private void invoke(ProcessSheetServiceImpl impl, ProcessSheetRowRequest req) throws Throwable {
        Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "validateWorkflowRowIfApplicable", String.class, String.class, ProcessSheetRowRequest.class);
        m.setAccessible(true);
        try {
            m.invoke(impl, FACTORY_ID, PLAN_ID, req);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void invokeMulti(ProcessSheetServiceImpl impl, ProcessSheetRowRequest req) throws Throwable {
        Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "validateMultiOutputAgainstWorkflow", String.class, String.class, ProcessSheetRowRequest.class);
        m.setAccessible(true);
        try {
            m.invoke(impl, FACTORY_ID, PLAN_ID, req);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void invokeSubmission(ProcessSheetServiceImpl impl, ProcessSheetRowRequest req) throws Throwable {
        Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "validateWorkflowSubmissionSelections", String.class, String.class, ProcessSheetRowRequest.class);
        m.setAccessible(true);
        try {
            m.invoke(impl, FACTORY_ID, PLAN_ID, req);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private ProcessSheetRowRequest row(int processOrder, boolean finished, String unit, BigDecimal output) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setProcessOrder(processOrder);
        req.setProductTypeId("PT-out-" + processOrder);
        req.setFinished(finished);
        req.setUnit(unit);
        req.setOutputQuantity(output);
        return req;
    }

    private WorkflowClerkSheetConfigDTO config(int processOrder, boolean outFinished, String outUnit) {
        PortDescriptor output = PortDescriptor.builder()
                .workflowPortId("out-" + processOrder)
                .materialKind(outFinished ? "FINISHED_GOOD" : "SEMI_FINISHED")
                .skuId("PT-out-" + processOrder)
                .materialName(outFinished ? "成品" : "半成品")
                .unit(outUnit)
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
                .inputs(List.of())
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
    @DisplayName("未注入 workflowClerkSheetService → 放行 (向后兼容)")
    void noService_passes() throws Throwable {
        ProcessSheetServiceImpl impl = newImpl(null);
        assertDoesNotThrow(() -> invoke(impl, row(1, false, "kg", new BigDecimal("10"))));
    }

    @Test
    @DisplayName("service 返回 null (legacy 计划) → 放行, 不校验")
    void legacyPlan_passes() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(null);
        ProcessSheetServiceImpl impl = newImpl(svc);
        assertDoesNotThrow(() -> invoke(impl, row(1, true, "只", new BigDecimal("5"))));
    }

    @Test
    @DisplayName("DRAFT 行 (产出<=0) → 跳过校验 (即便类型不符也不查配置)")
    void draftRow_skipped() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        ProcessSheetServiceImpl impl = newImpl(svc);
        assertDoesNotThrow(() -> invoke(impl, row(1, true, "kg", BigDecimal.ZERO)));
        verify(svc, never()).getWorkflowSheetConfig(anyString(), anyString());
    }

    @Test
    @DisplayName("产出类型不符 (workflow=半成品, 行=成品) → 409 KIND_MISMATCH")
    void outputKindMismatch_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(1, false, "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke(impl, row(1, true, "kg", new BigDecimal("10"))));
        assertEquals(409, ex.getCode());
        assertEquals("WORKFLOW_ROW_OUTPUT_KIND_MISMATCH", ex.getErrorCode());
    }

    @Test
    @DisplayName("产出单位不符 (workflow=kg, 行=只) → 409 UNIT_MISMATCH")
    void outputUnitMismatch_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(2, false, "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke(impl, row(2, false, "只", new BigDecimal("10"))));
        assertEquals(409, ex.getCode());
        assertEquals("WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH", ex.getErrorCode());
    }

    @Test
    @DisplayName("类型+单位对齐 → 放行")
    void aligned_passes() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(3, false, "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        assertDoesNotThrow(() -> invoke(impl, row(3, false, "kg", new BigDecimal("10"))));
    }

    @Test
    @DisplayName("产出 SKU 必须与 Workflow 端口绑定一致")
    void outputSkuMismatch_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(3, false, "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        ProcessSheetRowRequest request = row(3, false, "kg", new BigDecimal("10"));
        request.setProductTypeId("PT-wrong");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke(impl, request));
        assertEquals("WORKFLOW_ROW_OUTPUT_SKU_MISMATCH", ex.getErrorCode());
    }

    @Test
    @DisplayName("该 processOrder 不在 workflow 图中 → 阻断, 不回退 legacy")
    void processOrderNotInWorkflow_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(1, false, "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke(impl, row(9, true, "只", new BigDecimal("10"))));
        assertEquals("PROCESS_SHEET_WORKFLOW_PROCESS_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("service 抛 BusinessException (多产出守卫) → 原样 surface")
    void serviceThrowsBusinessException_rethrown() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID))
                .thenThrow(new BusinessException(409, "多产出").withCode("WORKFLOW_MULTI_OUTPUT_UNSUPPORTED"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke(impl, row(1, false, "kg", new BigDecimal("10"))));
        assertEquals("WORKFLOW_MULTI_OUTPUT_UNSUPPORTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("多产出按 workflowPortId 绑定各自单位，不能使用顶层单位兜底")
    void multiOutput_usesEachWorkflowPortUnit() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        PortDescriptor input = PortDescriptor.builder()
                .workflowPortId("in-1").unit("g").required(true).build();
        PortDescriptor pieces = PortDescriptor.builder()
                .workflowPortId("out-pieces").materialName("成品")
                .skuId("PT-FG").unit("件").gramsPerUnit(new BigDecimal("500"))
                .required(true).finished(true).build();
        PortDescriptor scraps = PortDescriptor.builder()
                .workflowPortId("out-scraps").materialName("边角料")
                .skuId("PT-SCRAP").unit("g").required(true).finished(false).build();
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(2).inputs(List.of(input)).output(pieces)
                .outputs(List.of(pieces, scraps)).build();
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(
                WorkflowClerkSheetConfigDTO.builder().processes(List.of(descriptor)).build());

        ProcessSheetRowRequest.OutputLine finished = new ProcessSheetRowRequest.OutputLine();
        finished.setWorkflowPortId("out-pieces");
        finished.setProductTypeId("PT-FG");
        finished.setUnit("件");
        finished.setFinished(true);
        finished.setQuantity(BigDecimal.ONE);
        ProcessSheetRowRequest.OutputLine scrap = new ProcessSheetRowRequest.OutputLine();
        scrap.setWorkflowPortId("out-scraps");
        scrap.setProductTypeId("PT-SCRAP");
        scrap.setFinished(false);
        scrap.setQuantity(BigDecimal.ONE);
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setProcessOrder(2);
        request.setInputUnit("g");
        request.setOutputs(List.of(finished, scrap));

        invokeMulti(newImpl(svc), request);

        assertEquals("g", request.getInputUnit());
        assertEquals("件", finished.getUnit());
        assertEquals("g", scrap.getUnit());
        assertEquals(BigDecimal.ONE, request.getOutputQuantity());
    }

    @Test
    @DisplayName("多产出缺少 workflowPortId → 阻断而不是按数组序号猜测")
    void multiOutput_withoutPortId_throws() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        PortDescriptor input = PortDescriptor.builder()
                .workflowPortId("in-1").unit("g").required(true).build();
        PortDescriptor output = PortDescriptor.builder()
                .workflowPortId("out-1").skuId("PT-FG")
                .unit("件").required(true).finished(true).build();
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(1).inputs(List.of(input)).output(output).outputs(List.of(output)).build();
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(
                WorkflowClerkSheetConfigDTO.builder().processes(List.of(descriptor)).build());

        ProcessSheetRowRequest.OutputLine line = new ProcessSheetRowRequest.OutputLine();
        line.setUnit("件");
        line.setProductTypeId("PT-FG");
        line.setFinished(true);
        line.setQuantity(BigDecimal.ONE);
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setProcessOrder(1);
        request.setOutputs(List.of(line));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeMulti(newImpl(svc), request));
        assertEquals("PROCESS_SHEET_WORKFLOW_OUTPUT_PORT_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("草稿可保存不完整选择组，正式提交同一请求因缺少替代投入而失败")
    void draftIncompleteGroup_passesButSubmissionFails() throws Throwable {
        PortDescriptor inputA = groupedPort("in-a", "RAW-A", "EXACTLY_ONE", 1, 1, false);
        PortDescriptor inputB = groupedPort("in-b", "RAW-B", "EXACTLY_ONE", 1, 1, false);
        PortDescriptor output = groupedOutput("out-a", "PT-A", "OPTIONAL", 0, 2);
        PortDescriptor outputB = groupedOutput("out-b", "PT-B", "OPTIONAL", 0, 2);
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(4)
                .inputs(List.of(inputA, inputB))
                .output(output)
                .outputs(List.of(output, outputB))
                .build();
        WorkflowClerkSheetService svc = workflowService(descriptor);
        ProcessSheetRowRequest request = multiRequest(4, outputLine("out-a", "PT-A"));

        assertDoesNotThrow(() -> invokeMulti(newImpl(svc), request));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeSubmission(newImpl(svc), request));
        assertEquals("PROCESS_SHEET_WORKFLOW_SELECTION_GROUP_VIOLATION", ex.getErrorCode());
    }

    @Test
    @DisplayName("EXACTLY_ONE 替代投入按 workflowPortId 统计，选择其一可正式提交")
    void replacementInput_exactlyOneSelected_passesSubmission() throws Throwable {
        PortDescriptor inputA = groupedPort("in-a", "RAW-A", "EXACTLY_ONE", 1, 1, false);
        PortDescriptor inputB = groupedPort("in-b", "RAW-B", "EXACTLY_ONE", 1, 1, false);
        PortDescriptor output = ungroupedOutput("out-a", "PT-A");
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(5)
                .inputs(List.of(inputA, inputB))
                .output(output)
                .outputs(List.of(output))
                .build();
        ProcessSheetRowRequest request = multiRequest(5, outputLine("out-a", "PT-A"));
        ProcessSheetRowRequest.MaterialInputTotal selected = new ProcessSheetRowRequest.MaterialInputTotal();
        selected.setWorkflowPortId("in-b");
        selected.setMaterialTypeId("RAW-B");
        selected.setUnit("kg");
        selected.setQuantity(new BigDecimal("3"));
        request.setMaterialInputTotals(List.of(selected));

        assertDoesNotThrow(() -> invokeSubmission(newImpl(workflowService(descriptor)), request));
    }

    @Test
    @DisplayName("OPTIONAL 多产出正式报工只统计正数量已选端口")
    void optionalMultiOutput_onlyPositiveSelectionCounts() throws Throwable {
        PortDescriptor input = groupedPort("in-a", "RAW-A", "OPTIONAL", 0, 1, false);
        PortDescriptor outputA = groupedOutput("out-a", "PT-A", "OPTIONAL", 0, 2);
        PortDescriptor outputB = groupedOutput("out-b", "PT-B", "OPTIONAL", 0, 2);
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(6)
                .inputs(List.of(input))
                .output(outputA)
                .outputs(List.of(outputA, outputB))
                .build();
        ProcessSheetRowRequest.OutputLine zero = outputLine("out-a", "PT-A");
        zero.setQuantity(BigDecimal.ZERO);
        ProcessSheetRowRequest request = multiRequest(6, zero, outputLine("out-b", "PT-B"));

        assertDoesNotThrow(() -> invokeSubmission(newImpl(workflowService(descriptor)), request));
    }

    @Test
    @DisplayName("无选择组的 legacy 端口在正式提交时仍全部必填")
    void legacyUngroupedOutputs_allRemainRequired() throws Throwable {
        PortDescriptor input = groupedPort("in-a", "RAW-A", "OPTIONAL", 0, 1, false);
        PortDescriptor outputA = ungroupedOutput("out-a", "PT-A");
        PortDescriptor outputB = ungroupedOutput("out-b", "PT-B");
        ProcessDescriptor descriptor = ProcessDescriptor.builder()
                .processOrder(7)
                .inputs(List.of(input))
                .output(outputA)
                .outputs(List.of(outputA, outputB))
                .build();
        ProcessSheetRowRequest request = multiRequest(7, outputLine("out-a", "PT-A"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeSubmission(newImpl(workflowService(descriptor)), request));
        assertEquals("PROCESS_SHEET_WORKFLOW_REQUIRED_OUTPUT_MISSING", ex.getErrorCode());
    }

    private WorkflowClerkSheetService workflowService(ProcessDescriptor descriptor) {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(
                WorkflowClerkSheetConfigDTO.builder().processes(List.of(descriptor)).build());
        return svc;
    }

    private PortDescriptor groupedPort(
            String portId, String skuId, String mode, int min, int max, boolean finished) {
        return PortDescriptor.builder()
                .workflowPortId(portId)
                .materialName(portId)
                .skuId(skuId)
                .unit("kg")
                .required(false)
                .finished(finished)
                .selectionGroupId(portId.startsWith("in-") ? "inputs" : "outputs")
                .selectionGroupLabel(portId.startsWith("in-") ? "替代投入" : "可选产出")
                .selectionGroupMode(mode)
                .selectionGroupMinSelections(min)
                .selectionGroupMaxSelections(max)
                .build();
    }

    private PortDescriptor groupedOutput(String portId, String skuId, String mode, int min, int max) {
        return groupedPort(portId, skuId, mode, min, max, false);
    }

    private PortDescriptor ungroupedOutput(String portId, String skuId) {
        return PortDescriptor.builder()
                .workflowPortId(portId)
                .materialName(portId)
                .skuId(skuId)
                .unit("kg")
                .required(true)
                .finished(false)
                .build();
    }

    private ProcessSheetRowRequest.OutputLine outputLine(String portId, String skuId) {
        ProcessSheetRowRequest.OutputLine line = new ProcessSheetRowRequest.OutputLine();
        line.setWorkflowPortId(portId);
        line.setProductTypeId(skuId);
        line.setUnit("kg");
        line.setFinished(false);
        line.setQuantity(BigDecimal.ONE);
        return line;
    }

    private ProcessSheetRowRequest multiRequest(
            int processOrder, ProcessSheetRowRequest.OutputLine... outputs) {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setProcessOrder(processOrder);
        request.setOutputs(List.of(outputs));
        return request;
    }
}
