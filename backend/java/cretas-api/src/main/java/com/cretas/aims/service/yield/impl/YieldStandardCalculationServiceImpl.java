package com.cretas.aims.service.yield.impl;

import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.yield.YieldStandardCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class YieldStandardCalculationServiceImpl implements YieldStandardCalculationService {

    private static final int MIN_SAMPLE_COUNT = 3;
    private static final BigDecimal PERCENTILE_20 = new BigDecimal("0.20");
    private static final BigDecimal PERCENTILE_80 = new BigDecimal("0.80");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal SIXTY = new BigDecimal("60");

    private final ProductionReportRepository productionReportRepository;
    private final WorkProcessRepository workProcessRepository;

    @Override
    @Transactional
    public Result recalculateFactory(String factoryId) {
        Result result = new Result();
        Map<String, List<Map<String, Object>>> rowsByProcess = groupByWorkProcessId(
                productionReportRepository.findYieldStandardSamples(factoryId));

        for (Map.Entry<String, List<Map<String, Object>>> entry : rowsByProcess.entrySet()) {
            result.incrementProcessed();
            String workProcessId = entry.getKey();
            WorkProcess process = workProcessRepository.findByFactoryIdAndId(factoryId, workProcessId)
                    .orElse(null);
            if (process == null) {
                result.incrementFailed();
                log.warn("[YieldStandardCalculation] factory={} workProcess={} 不存在, skip",
                        factoryId, workProcessId);
                continue;
            }

            List<BatchStandardSample> samples = toValidSamples(entry.getValue());
            if (samples.size() < MIN_SAMPLE_COUNT) {
                result.incrementSkippedInsufficientData();
                continue;
            }

            CalculatedStandards standards = calculateStandards(samples);
            BigDecimal targetYieldMin = chooseExistingOrCalculated(
                    process.getStandardYieldMin(), standards.yieldMin());
            BigDecimal targetYieldMax = chooseExistingOrCalculated(
                    process.getStandardYieldMax(), standards.yieldMax());
            if (targetYieldMin != null
                    && targetYieldMax != null
                    && targetYieldMin.compareTo(targetYieldMax) >= 0) {
                result.incrementFailed();
                log.warn("[YieldStandardCalculation] factory={} workProcess={} 推算后区间无效 min={} max={}, skip",
                        factoryId, workProcessId, targetYieldMin, targetYieldMax);
                continue;
            }

            if (fillMissingStandards(process, standards)) {
                workProcessRepository.save(process);
                result.incrementUpdated();
                log.info("[YieldStandardCalculation] factory={} workProcess={} 系统推算 sampleCount={} min={} max={} hourly={}",
                        factoryId, workProcessId, samples.size(), standards.yieldMin(), standards.yieldMax(),
                        standards.hourlyRate());
            } else {
                result.incrementSkippedManual();
            }
        }

        return result;
    }

    private Map<String, List<Map<String, Object>>> groupByWorkProcessId(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String workProcessId = asString(row.get("work_process_id"));
            if (workProcessId == null || workProcessId.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(workProcessId, ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private List<BatchStandardSample> toValidSamples(List<Map<String, Object>> rows) {
        List<BatchStandardSample> samples = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal input = toBig(row.get("input_quantity"));
            BigDecimal output = toBig(row.get("output_quantity"));
            BigDecimal minutes = toBig(row.get("total_work_minutes"));
            BigDecimal workers = toBig(row.get("total_workers"));
            BigDecimal laborCost = toBig(row.get("labor_cost"));
            if (!isValidSample(input, output, minutes, workers, laborCost)) {
                continue;
            }

            BigDecimal yieldRate = output.divide(input, 8, RoundingMode.HALF_UP);
            BigDecimal laborHours = minutes.divide(SIXTY, 8, RoundingMode.HALF_UP).multiply(workers);
            BigDecimal hourlyRate = laborCost.divide(laborHours, 8, RoundingMode.HALF_UP);
            samples.add(new BatchStandardSample(yieldRate, hourlyRate));
        }
        return samples;
    }

    private static CalculatedStandards calculateStandards(List<BatchStandardSample> samples) {
        List<BigDecimal> yieldRates = samples.stream()
                .map(BatchStandardSample::yieldRate)
                .toList();
        List<BigDecimal> hourlyRates = samples.stream()
                .map(BatchStandardSample::hourlyRate)
                .toList();

        return new CalculatedStandards(
                percentile(yieldRates, PERCENTILE_20).setScale(4, RoundingMode.HALF_UP),
                percentile(yieldRates, PERCENTILE_80).setScale(4, RoundingMode.HALF_UP),
                median(hourlyRates).setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal chooseExistingOrCalculated(BigDecimal existing, BigDecimal calculated) {
        return existing == null ? calculated : existing;
    }

    private static boolean fillMissingStandards(WorkProcess process, CalculatedStandards standards) {
        boolean changed = false;
        if (process.getStandardYieldMin() == null) {
            process.setStandardYieldMin(standards.yieldMin());
            changed = true;
        }
        if (process.getStandardYieldMax() == null) {
            process.setStandardYieldMax(standards.yieldMax());
            changed = true;
        }
        if (process.getStandardHourlyRate() == null) {
            process.setStandardHourlyRate(standards.hourlyRate());
            changed = true;
        }
        return changed;
    }

    private static BigDecimal percentile(List<BigDecimal> values, BigDecimal percentile) {
        List<BigDecimal> sorted = values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }

        BigDecimal position = percentile.multiply(BigDecimal.valueOf(sorted.size() - 1));
        int lowerIndex = position.setScale(0, RoundingMode.FLOOR).intValue();
        int upperIndex = position.setScale(0, RoundingMode.CEILING).intValue();
        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }

        BigDecimal fraction = position.subtract(BigDecimal.valueOf(lowerIndex));
        BigDecimal lower = sorted.get(lowerIndex);
        BigDecimal upper = sorted.get(upperIndex);
        return lower.add(upper.subtract(lower).multiply(fraction));
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(TWO, 8, RoundingMode.HALF_UP);
    }

    private static boolean isValidSample(
            BigDecimal input,
            BigDecimal output,
            BigDecimal minutes,
            BigDecimal workers,
            BigDecimal laborCost) {
        return isPositive(input)
                && isPositive(output)
                && isPositive(minutes)
                && isPositive(workers)
                && isPositive(laborCost);
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal toBig(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private record CalculatedStandards(BigDecimal yieldMin, BigDecimal yieldMax, BigDecimal hourlyRate) {
    }

    private record BatchStandardSample(BigDecimal yieldRate, BigDecimal hourlyRate) {
    }
}
