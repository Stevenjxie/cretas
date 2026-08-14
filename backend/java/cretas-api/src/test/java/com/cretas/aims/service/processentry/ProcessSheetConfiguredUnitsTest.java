package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessSheetConfiguredUnitsTest {

    @Test
    void normalizesRequestUnitsFromProductProcessConfiguration() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("each");
        request.setOutputUnit("bag");

        configuredUnitsMethod().invoke(null, request, productProcess("each"), workProcess("each", "bag"));

        assertEquals("each", request.getInputUnit());
        assertEquals("bag", request.getOutputUnit());
        assertEquals("bag", request.getUnit());
    }

    @Test
    void rejectsClientUnitThatDiffersFromConfiguredProcess() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("kg");
        request.setOutputUnit("box");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> configuredUnitsMethod().invoke(null, request, productProcess("each"), workProcess("each", "bag")));

        BusinessException error = (BusinessException) thrown.getCause();
        assertEquals("PROCESS_SHEET_UNIT_MISMATCH", error.getErrorCode());
    }

    @Test
    void acceptsChineseDisplayAliasForCanonicalConfiguredUnit() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("kg");
        request.setOutputUnit("盒");
        request.setUnit("盒");

        configuredUnitsMethod().invoke(null, request, productProcess("kg"), workProcess("kg", "box"));

        assertEquals("kg", request.getInputUnit());
        assertEquals("box", request.getOutputUnit());
        assertEquals("box", request.getUnit());
    }

    @Test
    void doesNotTreatCaseAsAliasForBox() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("kg");
        request.setOutputUnit("箱");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> configuredUnitsMethod().invoke(null, request, productProcess("kg"), workProcess("kg", "box")));

        BusinessException error = (BusinessException) thrown.getCause();
        assertEquals("PROCESS_SHEET_UNIT_MISMATCH", error.getErrorCode());
    }

    @Test
    void rejectsExternalSemiFinishedFeedForNonKgConfiguredInput() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("bag");
        ProcessSheetRowRequest.UpstreamRef source = new ProcessSheetRowRequest.UpstreamRef();
        source.setSemiFinished(true);
        request.setUpstreamSources(List.of(source));

        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "assertExternalFeedUnitSupported", ProcessSheetRowRequest.class);
        method.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, request));

        BusinessException error = (BusinessException) thrown.getCause();
        assertEquals("PROCESS_SHEET_EXTERNAL_FEED_UNIT_UNSUPPORTED", error.getErrorCode());
    }

    @Test
    void treatsCanonicalPackagingAliasesAsTheSameNativeStockUnit() throws Exception {
        // 2026-08-14: 该方法由 static 改成实例方法并多收一个 factoryId ——
        // 判等从私有折叠表换成权威表 (configuredUnitsEquivalent)。这三条断的是
        // 「同一个单位的中英两种写法」, 换表之后必须照旧放行, 否则就是把 2026-07-31
        // 那条误拦引回来了。
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "convertReportingQuantityToStorage",
                String.class, BigDecimal.class, String.class, String.class, String.class);
        method.setAccessible(true);
        ProcessSheetServiceImpl impl = org.mockito.Mockito.mock(
                ProcessSheetServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertEquals(new BigDecimal("10"), method.invoke(
                impl, "F006", new BigDecimal("10"), "box", "盒", "成品盒"));
        assertEquals(new BigDecimal("10"), method.invoke(
                impl, "F006", new BigDecimal("10"), "slice", "片", "封膜"));
        assertEquals(new BigDecimal("1.25"), method.invoke(
                impl, "F006", new BigDecimal("1.25"), "case", "箱", "外箱"));
    }

    private static Method configuredUnitsMethod() throws Exception {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "normalizeConfiguredUnits", ProcessSheetRowRequest.class, ProductWorkProcess.class, WorkProcess.class);
        method.setAccessible(true);
        return method;
    }

    private static ProductWorkProcess productProcess(String unitOverride) {
        ProductWorkProcess process = new ProductWorkProcess();
        process.setUnitOverride(unitOverride);
        return process;
    }

    private static WorkProcess workProcess(String inputUnit, String outputUnit) {
        WorkProcess process = new WorkProcess();
        process.setUnit(inputUnit);
        process.setOutputUnit(outputUnit);
        process.setProcessName("test process");
        return process;
    }
}
