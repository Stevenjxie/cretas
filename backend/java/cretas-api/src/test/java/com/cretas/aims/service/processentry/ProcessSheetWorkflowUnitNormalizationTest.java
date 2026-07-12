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
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(3, true, "kg", "盒"));
        ProcessSheetServiceImpl impl = newImpl(svc);

        ProcessSheetRowRequest req = row(3, "盒", "kg", "盒");
        assertTrue(apply(impl, req));
        assertEquals("kg", req.getInputUnit());
        assertEquals("盒", req.getOutputUnit());
        assertEquals("盒", req.getUnit());
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
    @DisplayName("该 processOrder 无描述符 → 返回 false, 交回 legacy 分支")
    void processOrderNotInWorkflow_returnsFalse() throws Throwable {
        WorkflowClerkSheetService svc = mock(WorkflowClerkSheetService.class);
        when(svc.getWorkflowSheetConfig(FACTORY_ID, PLAN_ID)).thenReturn(config(1, false, "kg", "kg"));
        ProcessSheetServiceImpl impl = newImpl(svc);
        assertFalse(apply(impl, row(9, "盒", "kg", "盒")));
    }
}
