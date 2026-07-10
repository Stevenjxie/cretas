package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
