package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.LaborSegment;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ProcessSheet 多产出逐产出字段投影")
class ProcessSheetMultiOutputProjectionTest {

    private ProcessSheetServiceImpl service() {
        return new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private ProcessSheetRowRequest synthesize(
            ProcessSheetRowRequest base,
            ProcessSheetRowRequest.OutputLine output,
            int index,
            boolean carryInputs) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "synthesizeOutputRequest",
                ProcessSheetRowRequest.class,
                ProcessSheetRowRequest.OutputLine.class,
                int.class,
                boolean.class);
        method.setAccessible(true);
        try {
            return (ProcessSheetRowRequest) method.invoke(service(), base, output, index, carryInputs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private BigDecimal yieldRate(ProcessSheetRowRequest request) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "yieldRate", ProcessSheetRowRequest.class);
        method.setAccessible(true);
        try {
            return (BigDecimal) method.invoke(service(), request);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void everyOutputKeepsGroupDateInputDenominatorAndOwnLaborByproduct() throws Throwable {
        ProcessSheetRowRequest base = new ProcessSheetRowRequest();
        base.setClientRowId("group-1");
        base.setProcessCode("cut");
        base.setProcessOrder(3);
        base.setProcessName("分切");
        base.setProcessDate(LocalDate.of(2026, 7, 17));
        base.setInputQuantity(new BigDecimal("10"));
        base.setInputUnit("kg");
        ProcessSheetRowRequest.RawInput raw = new ProcessSheetRowRequest.RawInput();
        raw.setMaterialBatchId("MB-1");
        raw.setQuantity(new BigDecimal("10"));
        base.setRawMaterialInputs(List.of(raw));

        LaborSegment labor = new LaborSegment();
        labor.setStartTime("08:00");
        labor.setEndTime("09:30");
        labor.setWorkerCount(2);
        ProcessChainEntryRequest.Byproduct byproduct = new ProcessChainEntryRequest.Byproduct();
        byproduct.setName("料头");
        byproduct.setQuantity(new BigDecimal("0.5"));
        byproduct.setUnit("kg");
        byproduct.setUnitPrice(new BigDecimal("3"));
        ProcessSheetRowRequest.OutputLine output = new ProcessSheetRowRequest.OutputLine();
        output.setProductTypeId("PT-2");
        output.setQuantity(new BigDecimal("4"));
        output.setUnit("kg");
        output.setLaborSegments(List.of(labor));
        output.setByproducts(List.of(byproduct));

        ProcessSheetRowRequest projected = synthesize(base, output, 1, false);

        assertEquals("group-1#1", projected.getClientRowId());
        assertEquals(LocalDate.of(2026, 7, 17), projected.getProcessDate());
        assertThat(projected.getInputQuantity()).isEqualByComparingTo("10");
        assertThat(projected.getTotalLaborHours()).isEqualByComparingTo("3.0000");
        assertThat(projected.getLaborSegments()).containsExactly(labor);
        assertThat(projected.getByproducts()).containsExactly(byproduct);
        assertThat(projected.getInputLineageRawMaterialInputs()).containsExactly(raw);
        assertNull(projected.getRawMaterialInputs(), "非首产出不应再生成真实消费边");
    }

    @Test
    void invalidByproductRecoveryPriceFailsLoudly() throws Exception {
        ProcessSheetRowRequest.OutputLine output = new ProcessSheetRowRequest.OutputLine();
        ProcessChainEntryRequest.Byproduct byproduct = new ProcessChainEntryRequest.Byproduct();
        byproduct.setName("料头");
        byproduct.setQuantity(BigDecimal.ONE);
        byproduct.setUnit("kg");
        byproduct.setUnitPrice(new BigDecimal("-1"));
        output.setByproducts(List.of(byproduct));
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "validateOutputDetails", ProcessSheetRowRequest.OutputLine.class);
        method.setAccessible(true);

        InvocationTargetException invocation = assertThrows(
                InvocationTargetException.class, () -> method.invoke(service(), output));
        assertThat(invocation.getCause()).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertEquals("PROCESS_SHEET_OUTPUT_BYPRODUCT_INVALID", error.getErrorCode()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void moneyAllocationAssignsRoundingRemainderToLastOutput() throws Exception {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "allocateMoney", BigDecimal.class, List.class);
        method.setAccessible(true);
        List<BigDecimal> allocated = (List<BigDecimal>) method.invoke(
                null,
                new BigDecimal("100.00"),
                List.of(new BigDecimal("0.333333333333"), new BigDecimal("0.666666666667")));

        assertThat(allocated).containsExactly(new BigDecimal("33.33"), new BigDecimal("66.67"));
        assertThat(allocated.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void yieldRateNormalizesGramAndKilogram() throws Throwable {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputQuantity(BigDecimal.ONE);
        request.setInputUnit("kg");
        request.setOutputQuantity(new BigDecimal("500"));
        request.setOutputUnit("g");

        assertThat(yieldRate(request)).isEqualByComparingTo("50.0000");
    }

    @Test
    void yieldRateUsesFinishedProductWeightForCountOutput() throws Throwable {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputQuantity(BigDecimal.ONE);
        request.setInputUnit("kg");
        request.setOutputQuantity(new BigDecimal("8"));
        request.setOutputUnit("盒");
        request.setProductWeight(new BigDecimal("0.4"));

        assertThat(yieldRate(request)).isEqualByComparingTo("40.0000");
    }

    @Test
    void yieldRateAllowsSameNonMassUnitAndRejectsIncompatibleDimensions() throws Throwable {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputQuantity(new BigDecimal("10"));
        request.setInputUnit("件");
        request.setOutputQuantity(new BigDecimal("8"));
        request.setOutputUnit("件");
        assertThat(yieldRate(request)).isEqualByComparingTo("80.0000");

        request.setInputUnit("kg");
        request.setOutputUnit("盒");
        assertNull(yieldRate(request));
    }
}
