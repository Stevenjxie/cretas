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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class YieldStandardCalculationServiceImpl implements YieldStandardCalculationService {

    private static final int MIN_SAMPLE_COUNT = 3;
    private static final BigDecimal PERCENTILE_20 = new BigDecimal("0.20");
    private static final BigDecimal PERCENTILE_80 = new BigDecimal("0.80");

    private final ProductionReportRepository productionReportRepository;
    private final WorkProcessRepository workProcessRepository;

    @Override
    @Transactional
    public Result recalculateFactory(String factoryId) {
        Result result = new Result();
        Map<String, List<Map<String, Object>>> rowsByProcess = groupByWorkProcessId(
                productionReportRepository.findYieldStandardSamples(factoryId));

        // I-3: batch-load all WorkProcess entities in one query to avoid N+1
        Set<String> workProcessIds = rowsByProcess.keySet();
        Map<String, WorkProcess> processMap = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, new ArrayList<>(workProcessIds))
                .stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp));

        for (Map.Entry<String, List<Map<String, Object>>> entry : rowsByProcess.entrySet()) {
            result.incrementProcessed();
            String workProcessId = entry.getKey();
            WorkProcess process = processMap.get(workProcessId);
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
            // I-1: allow min == max (stable processes like blanching that always hit 0.95)
            // only reject strictly invalid ranges where min > max
            if (targetYieldMin != null
                    && targetYieldMax != null
                    && targetYieldMin.compareTo(targetYieldMax) > 0) {
                result.incrementFailed();
                log.warn("[YieldStandardCalculation] factory={} workProcess={} 推算后区间无效 min={} max={}, skip",
                        factoryId, workProcessId, targetYieldMin, targetYieldMax);
                continue;
            }

            if (fillMissingStandards(process, standards)) {
                workProcessRepository.save(process);
                result.incrementUpdated();
                log.info("[YieldStandardCalculation] factory={} workProcess={} 系统推算 sampleCount={} min={} max={}",
                        factoryId, workProcessId, samples.size(), standards.yieldMin(), standards.yieldMax());
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
            // B-1: labor_cost is itself derived from standardHourlyRate at report time
            // (YieldReportServiceImpl: labor_cost = workers × minutes × standardHourlyRate).
            // Reverse-computing labor_cost / (workers × hours) just reads back the original
            // rate — it is a tautology, not new information. Worse, when standardHourlyRate
            // is null (cold-start), labor_cost is null too, so every sample is filtered out
            // and the auto-calculation never writes a rate — the exact scenario we are trying
            // to bootstrap. Solution: yield rate does not depend on labor_cost at all;
            // we drop the hourly-rate calculation entirely and only compute yieldRate here.
            if (!isPositive(input) || !isPositive(output)) {
                continue;
            }

            BigDecimal yieldRate = output.divide(input, 8, RoundingMode.HALF_UP);
            samples.add(new BatchStandardSample(yieldRate));
        }
        return samples;
    }

    private static CalculatedStandards calculateStandards(List<BatchStandardSample> samples) {
        List<BigDecimal> yieldRates = samples.stream()
                .map(BatchStandardSample::yieldRate)
                .toList();

        // B-1: hourlyRate is intentionally not calculated here — see toValidSamples comment.
        return new CalculatedStandards(
                percentile(yieldRates, PERCENTILE_20).setScale(4, RoundingMode.HALF_UP),
                percentile(yieldRates, PERCENTILE_80).setScale(4, RoundingMode.HALF_UP));
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
        // B-1: standardHourlyRate is intentionally NOT auto-filled here.
        // labor_cost in reports is derived from standardHourlyRate at report-write time
        // (workers × minutes × rate). Computing rate = labor_cost / (workers × hours)
        // would just read back the original value — a tautology with no new information.
        // During cold-start (rate is null), labor_cost is also null, so no samples
        // survive filtering and the auto-calculation can never bootstrap the rate anyway.
        // standardHourlyRate remains a manual-only field.
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

    private record CalculatedStandards(BigDecimal yieldMin, BigDecimal yieldMax) {
    }

    // B-1: hourlyRate removed — yield calculation does not depend on labor cost.
    // standardHourlyRate is a manual-only field (see fillMissingStandards).
    private record BatchStandardSample(BigDecimal yieldRate) {
    }
}
