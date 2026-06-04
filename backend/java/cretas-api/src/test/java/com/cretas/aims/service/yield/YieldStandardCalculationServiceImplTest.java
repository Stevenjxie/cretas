package com.cretas.aims.service.yield;

import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.yield.impl.YieldStandardCalculationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("D4 出成率上下限自动计算")
@ExtendWith(MockitoExtension.class)
class YieldStandardCalculationServiceImplTest {

    @Mock
    private ProductionReportRepository productionReportRepository;

    @Mock
    private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private YieldStandardCalculationServiceImpl service;

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("按批样本计算 P20/P80 出成率并写回空字段；standardHourlyRate 不被自动填写（手填字段）")
    void calculatePercentilesAndDoNotTouchHourlyRate() {
        WorkProcess process = process("wp-1");
        when(productionReportRepository.findYieldStandardSamples("F006"))
                .thenReturn(List.of(
                        // B-1: samples only need input/output — no laborCost required
                        sample("wp-1", 1L, "100", "60"),
                        sample("wp-1", 2L, "100", "70"),
                        sample("wp-1", 3L, "100", "80"),
                        sample("wp-1", 4L, "100", "90"),
                        sample("wp-1", 5L, "100", "100")
                ));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq("F006"), anyList()))
                .thenReturn(List.of(process));
        when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

        YieldStandardCalculationService.Result result = service.recalculateFactory("F006");

        assertThat(result.getUpdated()).isEqualTo(1);
        // P20 of [0.60, 0.70, 0.80, 0.90, 1.00] → 0.68
        assertThat(process.getStandardYieldMin()).isEqualByComparingTo("0.6800");
        // P80 of [0.60, 0.70, 0.80, 0.90, 1.00] → 0.92
        assertThat(process.getStandardYieldMax()).isEqualByComparingTo("0.9200");
        // B-1: standardHourlyRate must NOT be written by the auto-calculation
        assertThat(process.getStandardHourlyRate()).isNull();
        verify(workProcessRepository).save(process);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // I-1: zero-variance (min == max) must succeed, not be rejected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("I-1: 零方差工序（所有样本出成率相同）能正常写入 min==max，不报 failed")
    void zeroVarianceProcessWritesMinEqualsMax() {
        // Blanching / cutting — consistently 0.70 across all batches
        WorkProcess process = process("wp-blanch");
        when(productionReportRepository.findYieldStandardSamples("F006"))
                .thenReturn(List.of(
                        sample("wp-blanch", 1L, "100", "70"),
                        sample("wp-blanch", 2L, "100", "70"),
                        sample("wp-blanch", 3L, "100", "70"),
                        sample("wp-blanch", 4L, "100", "70"),
                        sample("wp-blanch", 5L, "100", "70")
                ));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq("F006"), anyList()))
                .thenReturn(List.of(process));
        when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

        YieldStandardCalculationService.Result result = service.recalculateFactory("F006");

        // Must be updated, not failed
        assertThat(result.getFailed()).isEqualTo(0);
        assertThat(result.getUpdated()).isEqualTo(1);
        // P20 == P80 == 0.70 for a zero-variance set
        assertThat(process.getStandardYieldMin()).isEqualByComparingTo("0.7000");
        assertThat(process.getStandardYieldMax()).isEqualByComparingTo("0.7000");
        assertThat(process.getStandardHourlyRate()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B-1: partial manual — only min is filled; max should be written, rate must not
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B-1: min 已手填、max 和 rate 为 null → 只写 max，不写 standardHourlyRate")
    void partialManualFillsOnlyMissingYield() {
        WorkProcess process = process("wp-1");
        process.setStandardYieldMin(new BigDecimal("0.5000")); // manual — must not change
        // standardYieldMax == null → should be auto-filled
        // standardHourlyRate == null → must NOT be auto-filled (B-1)
        when(productionReportRepository.findYieldStandardSamples("F006"))
                .thenReturn(List.of(
                        sample("wp-1", 1L, "100", "60"),
                        sample("wp-1", 2L, "100", "70"),
                        sample("wp-1", 3L, "100", "80"),
                        sample("wp-1", 4L, "100", "90")
                ));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq("F006"), anyList()))
                .thenReturn(List.of(process));
        when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

        YieldStandardCalculationService.Result result = service.recalculateFactory("F006");

        assertThat(result.getUpdated()).isEqualTo(1);
        // Manual min must be preserved
        assertThat(process.getStandardYieldMin()).isEqualByComparingTo("0.5000");
        // Max was null → auto-filled with P80 of [0.60, 0.70, 0.80, 0.90] → 0.87
        assertThat(process.getStandardYieldMax()).isNotNull();
        assertThat(process.getStandardYieldMax()).isGreaterThan(new BigDecimal("0.5000"));
        // B-1: rate must remain null
        assertThat(process.getStandardHourlyRate()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Insufficient data
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("数据不足 3 批时不写入 0 或假值")
    void skipWhenSampleCountIsBelowThree() {
        WorkProcess process = process("wp-1");
        when(productionReportRepository.findYieldStandardSamples("F006"))
                .thenReturn(List.of(
                        sample("wp-1", 1L, "100", "60"),
                        sample("wp-1", 2L, "100", "70")
                ));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq("F006"), anyList()))
                .thenReturn(List.of(process));

        YieldStandardCalculationService.Result result = service.recalculateFactory("F006");

        assertThat(result.getSkippedInsufficientData()).isEqualTo(1);
        assertThat(process.getStandardYieldMin()).isNull();
        assertThat(process.getStandardYieldMax()).isNull();
        assertThat(process.getStandardHourlyRate()).isNull();
        verify(workProcessRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manual values preserved in full
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("min/max 均已手填时所有字段保持原值，不触发 save")
    void keepManualValuesWhenAllYieldFieldsFilled() {
        WorkProcess process = process("wp-1");
        process.setStandardYieldMin(new BigDecimal("0.5000"));
        process.setStandardYieldMax(new BigDecimal("0.9500"));
        when(productionReportRepository.findYieldStandardSamples("F006"))
                .thenReturn(List.of(
                        sample("wp-1", 1L, "100", "60"),
                        sample("wp-1", 2L, "100", "70"),
                        sample("wp-1", 3L, "100", "80"),
                        sample("wp-1", 4L, "100", "90")
                ));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq("F006"), anyList()))
                .thenReturn(List.of(process));

        YieldStandardCalculationService.Result result = service.recalculateFactory("F006");

        assertThat(result.getSkippedManual()).isEqualTo(1);
        assertThat(process.getStandardYieldMin()).isEqualByComparingTo("0.5000");
        assertThat(process.getStandardYieldMax()).isEqualByComparingTo("0.9500");
        assertThat(process.getStandardHourlyRate()).isNull();
        verify(workProcessRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Incomplete samples filtered (B-1: only input/output must be positive)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("零投入或零产出的批次被过滤；工时/人数缺失不再影响有效性")
    void ignoreIncompleteSamplesBeforeCalculating() {
        WorkProcess process = process("wp-1");
        when(productionReportRepository.findYieldStandardSamples("F006"))
                .thenReturn(List.of(
                        sample("wp-1", 1L, "100", "60"),   // valid
                        sample("wp-1", 2L, "0", "70"),     // input=0 → filtered
                        sample("wp-1", 3L, "100", "0"),    // output=0 → filtered
                        sample("wp-1", 4L, "100", "90"),   // valid (no minutes/workers needed)
                        sample("wp-1", 5L, "100", "100")   // valid
                ));
        when(workProcessRepository.findByFactoryIdAndIdIn(eq("F006"), anyList()))
                .thenReturn(List.of(process));
        when(workProcessRepository.save(any(WorkProcess.class))).thenAnswer(inv -> inv.getArgument(0));

        // 3 valid samples (1, 4, 5) → min/max should be written
        YieldStandardCalculationService.Result result = service.recalculateFactory("F006");

        assertThat(result.getUpdated()).isEqualTo(1);
        assertThat(process.getStandardYieldMin()).isNotNull();
        assertThat(process.getStandardYieldMax()).isNotNull();
        assertThat(process.getStandardHourlyRate()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static WorkProcess process(String id) {
        return WorkProcess.builder()
                .id(id)
                .factoryId("F006")
                .processName("焯水")
                .unit("kg")
                .isActive(true)
                .build();
    }

    /** Minimal sample with only input/output — sufficient for yield calculation (B-1). */
    private static Map<String, Object> sample(
            String workProcessId,
            Long batchId,
            String inputQuantity,
            String outputQuantity) {
        return Map.of(
                "work_process_id", workProcessId,
                "batch_id", batchId,
                "input_quantity", new BigDecimal(inputQuantity),
                "output_quantity", new BigDecimal(outputQuantity));
    }
}
