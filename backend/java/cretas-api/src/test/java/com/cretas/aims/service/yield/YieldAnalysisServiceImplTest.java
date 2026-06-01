package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.impl.YieldAnalysisServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YieldAnalysisServiceImplTest {

    @Mock
    ProductionReportRepository reportRepo;

    @InjectMocks
    YieldAnalysisServiceImpl service;

    /** Build a result row matching the native query projection keys. */
    private static Map<String, Object> row(String processName,
                                           String totalInput, String totalOutput,
                                           String inputUnit, String outputUnit,
                                           Long batchCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("process_name", processName);
        m.put("total_input",  new BigDecimal(totalInput));
        m.put("total_output", new BigDecimal(totalOutput));
        m.put("input_unit",   inputUnit);
        m.put("output_unit",  outputUnit);
        m.put("batch_count",  batchCount);
        return m;
    }

    @Test
    void aggregate_sameUnit_computesConversionAndWastage() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(row("处理", "300", "260", "kg", "kg", 2L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess(
                "F006",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getProcessName()).isEqualTo("处理");
        assertThat(dto.getUnitComparable()).isTrue();
        assertThat(dto.getConversionRate()).isNotNull();
        assertThat(dto.getConversionRate()).isEqualByComparingTo("86.7");
        assertThat(dto.getWastageRate()).isNotNull();
        assertThat(dto.getWastageRate()).isEqualByComparingTo("13.3");
        assertThat(dto.getBatchCount()).isEqualTo(2);
    }

    @Test
    void aggregate_crossUnit_conversionNull() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(row("末道", "998", "3184", "kg", "盒", 1L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess(
                "F006",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getUnitComparable()).isFalse();
        assertThat(dto.getConversionRate()).isNull();
        assertThat(dto.getWastageRate()).isNull();
    }

    @Test
    void aggregate_nullParams_convertedToSentinels() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString()))
                .thenReturn(Collections.emptyList());

        List<ProcessYieldAggDTO> result = service.aggregateByProcess("F001", null, null, null);

        assertThat(result).isEmpty();
        verify(reportRepo).aggregateYieldByProcess(
                eq("F001"),
                eq(LocalDate.of(1900, 1, 1)),
                eq(LocalDate.of(2999, 12, 31)),
                eq(""));
    }
}
