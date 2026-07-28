package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.impl.WorkReportingServiceImpl;
import com.cretas.aims.repository.ProductionReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 效率对比工具
 *
 * 对比不同时间段或不同工人的生产效率，包含产出效率、良品率、
 * 人均产出、工时利用率等指标。支持时间周期对比和工人排名。
 *
 * @author Cretas Team
 * @since 2026-03-11
 */
@Slf4j
@Component
public class EfficiencyComparisonTool extends AbstractBusinessTool {

    @Autowired
    private WorkReportingServiceImpl workReportingService;

    @Autowired
    private ProductionReportRepository reportRepository;

    @Override
    public String getToolName() {
        return "efficiency_comparison";
    }

    @Override
    public String getDescription() {
        return "对比生产效率，支持按时间段对比（本周vs上周、本月vs上月）和工人效率排名。" +
                "包含产出效率、良品率、人均产出、工时利用率等指标。" +
                "适用场景：效率对比、产能分析、绩效排名、效率趋势分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> period = new HashMap<>();
        period.put("type", "string");
        period.put("description", "对比周期：week(本周vs上周), month(本月vs上月), quarter(本季度vs上季度)");
        period.put("enum", Arrays.asList("week", "month", "quarter"));
        period.put("default", "week");
        properties.put("period", period);

        Map<String, Object> compareType = new HashMap<>();
        compareType.put("type", "string");
        compareType.put("description", "对比类型：time_period(时间段对比), worker_ranking(工人效率排名), both(两者都要)");
        compareType.put("enum", Arrays.asList("time_period", "worker_ranking", "both"));
        compareType.put("default", "both");
        properties.put("compareType", compareType);

        Map<String, Object> topN = new HashMap<>();
        topN.put("type", "integer");
        topN.put("description", "工人排名展示前N名，默认10");
        topN.put("default", 10);
        properties.put("topN", topN);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行效率对比 - 工厂ID: {}, 参数: {}", factoryId, params);

        String period = getString(params, "period", "week");
        String compareType = getString(params, "compareType", "both");
        int topN = getInteger(params, "topN", 10);

        // 计算当前和上一周期的日期范围
        LocalDate now = LocalDate.now();
        LocalDate[] currentRange = calculatePeriodRange(now, period);
        LocalDate[] previousRange = calculatePreviousPeriodRange(currentRange[0], currentRange[1], period);

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "EFFICIENCY_COMPARISON");
        result.put("period", period);
        result.put("currentPeriod", Map.of(
                "startDate", currentRange[0].format(DateTimeFormatter.ISO_DATE),
                "endDate", currentRange[1].format(DateTimeFormatter.ISO_DATE)));
        result.put("previousPeriod", Map.of(
                "startDate", previousRange[0].format(DateTimeFormatter.ISO_DATE),
                "endDate", previousRange[1].format(DateTimeFormatter.ISO_DATE)));
        result.put("generatedAt", now.format(DateTimeFormatter.ISO_DATE));

        // 时间段对比
        if ("time_period".equals(compareType) || "both".equals(compareType)) {
            Map<String, Object> periodComparison = buildPeriodComparison(factoryId, currentRange, previousRange);
            result.put("periodComparison", periodComparison);
        }

        // 工人效率排名
        if ("worker_ranking".equals(compareType) || "both".equals(compareType)) {
            Map<String, Object> workerRanking = buildWorkerRanking(factoryId, currentRange, topN);
            result.put("workerRanking", workerRanking);
        }

        // 每日效率趋势
        List<Map<String, Object>> dailyTrend = reportRepository.getDailyEfficiencyTrend(
                factoryId, currentRange[0], currentRange[1]);
        result.put("dailyTrend", dailyTrend);

        log.info("效率对比完成 - 工厂ID: {}, 周期: {}", factoryId, period);
        return result;
    }

    private Map<String, Object> buildPeriodComparison(String factoryId, LocalDate[] currentRange, LocalDate[] previousRange) {
        Map<String, Object> currentProgress = reportRepository.getProgressSummary(factoryId, currentRange[0], currentRange[1]);
        Map<String, Object> previousProgress = reportRepository.getProgressSummary(factoryId, previousRange[0], previousRange[1]);
        Map<String, Object> currentHours = reportRepository.getHoursSummary(factoryId, currentRange[0], currentRange[1]);
        Map<String, Object> previousHours = reportRepository.getHoursSummary(factoryId, previousRange[0], previousRange[1]);

        Map<String, Object> comparison = new HashMap<>();

        // 产出对比
        BigDecimal curOutput = toBigDecimal(currentProgress, "total_output");
        BigDecimal prevOutput = toBigDecimal(previousProgress, "total_output");
        comparison.put("currentOutput", curOutput);
        comparison.put("previousOutput", prevOutput);
        comparison.put("outputChange", calculateChange(curOutput, prevOutput));

        // 良品率对比
        BigDecimal curGood = toBigDecimal(currentProgress, "total_good");
        BigDecimal prevGood = toBigDecimal(previousProgress, "total_good");
        BigDecimal curYieldRate = safeRate(curGood, curOutput);
        BigDecimal prevYieldRate = safeRate(prevGood, prevOutput);
        comparison.put("currentYieldRate", curYieldRate);
        comparison.put("previousYieldRate", prevYieldRate);
        comparison.put("yieldRateChange", curYieldRate.subtract(prevYieldRate));

        // 工时对比
        BigDecimal curMinutes = toBigDecimal(currentHours, "total_minutes");
        BigDecimal prevMinutes = toBigDecimal(previousHours, "total_minutes");
        comparison.put("currentWorkHours", curMinutes.divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));
        comparison.put("previousWorkHours", prevMinutes.divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));

        // 人均产出 (产出/工时)
        BigDecimal curProductivity = safeRate(curOutput, curMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        BigDecimal prevProductivity = safeRate(prevOutput, prevMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        comparison.put("currentProductivity", curProductivity);
        comparison.put("previousProductivity", prevProductivity);
        comparison.put("productivityChange", calculateChange(curProductivity, prevProductivity));

        return comparison;
    }

    private Map<String, Object> buildWorkerRanking(String factoryId, LocalDate[] range, int topN) {
        List<Map<String, Object>> ranking = reportRepository.getWorkerEfficiencyRanking(
                factoryId, range[0], range[1]);

        Map<String, Object> result = new HashMap<>();
        result.put("totalWorkers", ranking.size());

        // 限制返回前N名
        List<Map<String, Object>> topWorkers = ranking.size() > topN ? ranking.subList(0, topN) : ranking;

        // 增强排名数据：添加良品率和人均产出
        List<Map<String, Object>> enrichedRanking = new ArrayList<>();
        for (int i = 0; i < topWorkers.size(); i++) {
            Map<String, Object> worker = new HashMap<>(topWorkers.get(i));
            worker.put("rank", i + 1);

            BigDecimal output = toBigDecimal(worker, "total_output");
            BigDecimal good = toBigDecimal(worker, "total_good");
            BigDecimal minutes = toBigDecimal(worker, "total_minutes");

            worker.put("yieldRate", safeRate(good, output));
            if (minutes.compareTo(BigDecimal.ZERO) > 0) {
                worker.put("outputPerHour", output.divide(
                        minutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP),
                        1, RoundingMode.HALF_UP));
            } else {
                worker.put("outputPerHour", BigDecimal.ZERO);
            }
            enrichedRanking.add(worker);
        }

        result.put("ranking", enrichedRanking);
        return result;
    }

    private LocalDate[] calculatePeriodRange(LocalDate now, String period) {
        LocalDate start;
        switch (period) {
            case "month":
                start = now.withDayOfMonth(1);
                break;
            case "quarter":
                int quarterMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
                start = now.withMonth(quarterMonth).withDayOfMonth(1);
                break;
            case "week":
            default:
                start = now.minusDays(now.getDayOfWeek().getValue() - 1);
                break;
        }
        return new LocalDate[]{start, now};
    }

    private LocalDate[] calculatePreviousPeriodRange(LocalDate currentStart, LocalDate currentEnd, String period) {
        switch (period) {
            case "month":
                LocalDate prevMonthStart = currentStart.minusMonths(1);
                LocalDate prevMonthEnd = currentStart.minusDays(1);
                return new LocalDate[]{prevMonthStart, prevMonthEnd};
            case "quarter":
                LocalDate prevQuarterStart = currentStart.minusMonths(3);
                LocalDate prevQuarterEnd = currentStart.minusDays(1);
                return new LocalDate[]{prevQuarterStart, prevQuarterEnd};
            case "week":
            default:
                LocalDate prevWeekStart = currentStart.minusWeeks(1);
                LocalDate prevWeekEnd = currentStart.minusDays(1);
                return new LocalDate[]{prevWeekStart, prevWeekEnd};
        }
    }

    private BigDecimal toBigDecimal(Map<String, Object> map, String key) {
        if (map == null) return BigDecimal.ZERO;
        Object val = map.get(key);
        if (val == null) return BigDecimal.ZERO;
        return new BigDecimal(val.toString());
    }

    private BigDecimal safeRate(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return current.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "period", "请问您想对比哪个时间段？可选：本周vs上周、本月vs上月、本季度vs上季度。",
                "compareType", "请问您想看哪种对比？可选：时间段对比、工人效率排名、或两者都看。",
                "topN", "请问工人排名要展示前几名？默认前10名。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "period", "对比周期",
                "compareType", "对比类型",
                "topN", "排名数量"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
