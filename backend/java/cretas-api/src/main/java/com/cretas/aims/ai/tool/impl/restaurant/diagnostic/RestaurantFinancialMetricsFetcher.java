package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.entity.smartbi.SmartBiFinanceData;
import com.cretas.aims.entity.smartbi.enums.RecordType;
import com.cretas.aims.repository.smartbi.SmartBiFinanceDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 11.5 Phase 1 — fetch restaurant financial metrics from
 * {@code smart_bi_finance_data} and return as the camelCase dict shape
 * expected by Python's {@code store_pnl_one_pager} section
 * ({@code FinancialMetrics.to_dict()} contract per
 * {@code backend/python/smartbi/services/restaurant/analyzer.py}).
 *
 * <p>Mirrors the algorithm of
 * {@link com.cretas.aims.service.smartbi.impl.FinanceAnalysisServiceImpl#getProfitMetrics(String, LocalDate, LocalDate)}
 * — same source (REVENUE + COST records, latest-upload filter, abs cost),
 * but emits the camelCase keys Python expects ({@code revenue}, {@code foodCost},
 * {@code laborCost}, {@code rent}, {@code otherCost}, {@code netProfit},
 * {@code foodCostRatio}, {@code laborCostRatio}, {@code rentRatio},
 * {@code restaurantNetMargin}, {@code revenueChangePct}, {@code laborCostChangePct},
 * {@code foodCostChangePct}, {@code costRigidity}, {@code grossRevenue},
 * {@code grossMarginFolded}, {@code grossMarginUnfolded}).
 *
 * <p>Used by {@link RestaurantStorePnlOnePagerTool} to inject
 * {@code request.params["financial_metrics"]} so Python no longer returns
 * "未提供 financial_metrics" skip.
 *
 * <p>Returns {@link Optional#empty()} when no REVENUE+COST data exists for
 * the period — caller should NOT inject the empty value (let Python emit
 * its canonical "未提供 financial_metrics" skip message).
 *
 * @since 2026-05-23 (Sprint 11.5 Phase 1)
 */
@Slf4j
@Component
public class RestaurantFinancialMetricsFetcher {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** "YYYY-MM" or "YYYY年M月" parser for {@code month} param. */
    private static final Pattern MONTH_PATTERN = Pattern.compile(
        "^\\s*(\\d{4})\\s*[年\\-/]\\s*(\\d{1,2})\\s*月?\\s*$"
    );

    /**
     * Category keywords used to bucket SmartBiFinanceData rows into the
     * Python {@code financial_metrics} cost slots. Conservative — anything
     * not matched falls into {@code otherCost}.
     */
    private static final String[] FOOD_KEYWORDS = {"食材", "原材料", "食品", "饮料", "酒水", "菜品"};
    private static final String[] LABOR_KEYWORDS = {"人工", "工资", "薪", "员工", "劳务"};
    private static final String[] RENT_KEYWORDS = {"租金", "房租", "店租", "场地"};

    private final SmartBiFinanceDataRepository financeDataRepository;

    @Autowired
    public RestaurantFinancialMetricsFetcher(SmartBiFinanceDataRepository financeDataRepository) {
        this.financeDataRepository = financeDataRepository;
    }

    /**
     * Build the {@code financial_metrics} dict for Python's
     * {@code store_pnl_one_pager} section.
     *
     * @param factoryId Restaurant factory id (e.g. {@code RES_3101_009})
     * @param monthRaw  Optional month label ("上月" / "本月" / "2026-04" /
     *                  "2026年4月" / null → "上月")
     * @return Optional containing the camelCase dict; empty if no REVENUE
     *         and no COST data exist for the resolved month.
     */
    public Optional<Map<String, Object>> fetch(String factoryId, String monthRaw) {
        LocalDate[] range = resolveMonthRange(monthRaw);
        LocalDate start = range[0];
        LocalDate end = range[1];

        List<SmartBiFinanceData> revenueData = filterToLatestUpload(
                financeDataRepository.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                        factoryId, RecordType.REVENUE, start, end));
        List<SmartBiFinanceData> costData = filterToLatestUpload(
                financeDataRepository.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                        factoryId, RecordType.COST, start, end));

        if (revenueData.isEmpty() && costData.isEmpty()) {
            log.info("RestaurantFinancialMetricsFetcher: no REVENUE+COST data for factory={} range={}..{}",
                    factoryId, start, end);
            return Optional.empty();
        }

        // ─── Current period ───
        BigDecimal revenue = sumRevenueCategory(revenueData, "收入");
        BigDecimal foodCost = sumCostByKeywords(costData, FOOD_KEYWORDS);
        BigDecimal laborCost = sumCostByKeywords(costData, LABOR_KEYWORDS);
        BigDecimal rent = sumCostByKeywords(costData, RENT_KEYWORDS);
        BigDecimal totalCost = sumTotalCost(costData);
        BigDecimal categorizedCost = foodCost.add(laborCost).add(rent);
        BigDecimal otherCost = totalCost.subtract(categorizedCost).max(BigDecimal.ZERO);
        BigDecimal netProfit = sumRevenueCategory(revenueData, "净利");
        // If 净利 category was absent in REVENUE bucket, derive from revenue - totalCost.
        boolean netProfitFromData = revenueData.stream()
                .anyMatch(r -> r.getCategory() != null && r.getCategory().contains("净利"));
        if (!netProfitFromData) {
            netProfit = revenue.subtract(totalCost);
        }

        // ─── Previous period (for cost_rigidity / changePct) ───
        LocalDate[] prevRange = previousMonthRange(start, end);
        List<SmartBiFinanceData> prevRevenue = filterToLatestUpload(
                financeDataRepository.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                        factoryId, RecordType.REVENUE, prevRange[0], prevRange[1]));
        List<SmartBiFinanceData> prevCost = filterToLatestUpload(
                financeDataRepository.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                        factoryId, RecordType.COST, prevRange[0], prevRange[1]));
        BigDecimal prevRevenueAmt = sumRevenueCategory(prevRevenue, "收入");
        BigDecimal prevFoodCost = sumCostByKeywords(prevCost, FOOD_KEYWORDS);
        BigDecimal prevLaborCost = sumCostByKeywords(prevCost, LABOR_KEYWORDS);

        // ─── Ratios (percentage, 0-100 scale per Python contract) ───
        Double foodCostRatio = ratioPct(foodCost, revenue);
        Double laborCostRatio = ratioPct(laborCost, revenue);
        Double rentRatio = ratioPct(rent, revenue);
        Double netMargin = ratioPct(netProfit, revenue);

        // ─── Change ratios (0-1 scale per Python contract) ───
        Double revenueChangePct = changeRatio(revenue, prevRevenueAmt);
        Double laborChangePct = changeRatio(laborCost, prevLaborCost);
        Double foodChangePct = changeRatio(foodCost, prevFoodCost);

        // ─── cost_rigidity (Python rule: only when revenue dropped > 5%) ───
        Double costRigidity = null;
        if (revenueChangePct != null && revenueChangePct < -0.05
                && laborChangePct != null && revenueChangePct != 0) {
            costRigidity = laborChangePct / revenueChangePct;
        }

        // ─── Dual gross margin (folded == unfolded when no discount data) ───
        double revenueD = revenue.doubleValue();
        double foodCostD = foodCost.doubleValue();
        Double grossMarginFolded = (foodCost.signum() > 0 && revenueD > 0)
                ? (revenueD - foodCostD) / revenueD * 100.0
                : null;
        Double grossMarginUnfolded = grossMarginFolded;  // no gross_revenue data → equal
        Double grossRevenue = revenueD > 0 ? revenueD : null;

        // ─── Assemble camelCase dict (FinancialMetrics.to_dict() shape) ───
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("revenue", revenueD);
        metrics.put("foodCost", foodCost.signum() > 0 ? foodCostD : null);
        metrics.put("laborCost", laborCost.signum() > 0 ? laborCost.doubleValue() : null);
        metrics.put("rent", rent.signum() > 0 ? rent.doubleValue() : null);
        metrics.put("otherCost", otherCost.signum() > 0 ? otherCost.doubleValue() : null);
        metrics.put("netProfit", netProfit.signum() != 0 ? netProfit.doubleValue() : null);
        metrics.put("foodCostRatio", foodCostRatio);
        metrics.put("laborCostRatio", laborCostRatio);
        metrics.put("rentRatio", rentRatio);
        metrics.put("restaurantNetMargin", netMargin);
        metrics.put("costRigidity", costRigidity);
        metrics.put("revenueChangePct", revenueChangePct);
        metrics.put("laborCostChangePct", laborChangePct);
        metrics.put("foodCostChangePct", foodChangePct);
        metrics.put("grossRevenue", grossRevenue);
        metrics.put("grossMarginFolded", grossMarginFolded);
        metrics.put("grossMarginUnfolded", grossMarginUnfolded);

        log.info("RestaurantFinancialMetricsFetcher: factory={} range={}..{} revenue={} foodCost={} laborCost={} netProfit={}",
                factoryId, start, end, revenue, foodCost, laborCost, netProfit);

        return Optional.of(metrics);
    }

    /** Format the resolved range as Python {@code period} label "YYYY-MM". */
    public String formatPeriod(String monthRaw) {
        LocalDate[] range = resolveMonthRange(monthRaw);
        return range[0].format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT));
    }

    // ─── Internals ──────────────────────────────────────────────────────

    /**
     * Resolve "上月"/"本月"/"YYYY-MM"/"YYYY年M月"/null → [monthStart, monthEnd].
     * Default: previous month (because Sprint 11 trigger phrases are
     * "上月损溢" / "上月成本" / "损益分析" — historical monthly P&L).
     */
    LocalDate[] resolveMonthRange(String raw) {
        LocalDate today = LocalDate.now();
        if (raw == null || raw.trim().isEmpty() || "上月".equals(raw.trim())) {
            LocalDate prev = today.minusMonths(1);
            return new LocalDate[]{prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth())};
        }
        String s = raw.trim();
        if ("本月".equals(s) || "this month".equalsIgnoreCase(s)) {
            return new LocalDate[]{today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth())};
        }
        Matcher m = MONTH_PATTERN.matcher(s);
        if (m.matches()) {
            try {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                LocalDate first = LocalDate.of(year, month, 1);
                return new LocalDate[]{first, first.withDayOfMonth(first.lengthOfMonth())};
            } catch (NumberFormatException | java.time.DateTimeException ignored) {
                // fall through to default
            }
        }
        LocalDate prev = today.minusMonths(1);
        return new LocalDate[]{prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth())};
    }

    private LocalDate[] previousMonthRange(LocalDate start, LocalDate end) {
        LocalDate prevFirst = start.minusMonths(1).withDayOfMonth(1);
        LocalDate prevLast = prevFirst.withDayOfMonth(prevFirst.lengthOfMonth());
        return new LocalDate[]{prevFirst, prevLast};
    }

    /**
     * Latest-upload filter — same algorithm as
     * {@link com.cretas.aims.service.smartbi.impl.FinanceAnalysisServiceImpl}.
     * Keep rows whose uploadId equals the max uploadId in the result set, so
     * multi-sheet uploads don't double-count.
     */
    private List<SmartBiFinanceData> filterToLatestUpload(List<SmartBiFinanceData> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Long maxUpload = rows.stream()
                .map(SmartBiFinanceData::getUploadId)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (maxUpload == null) {
            return rows;
        }
        return rows.stream()
                .filter(r -> maxUpload.equals(r.getUploadId()))
                .toList();
    }

    /** Sum {@code actualAmount} for REVENUE rows whose category contains {@code keyword}. */
    private BigDecimal sumRevenueCategory(List<SmartBiFinanceData> rows, String keyword) {
        return rows.stream()
                .filter(r -> r.getCategory() != null && r.getCategory().contains(keyword))
                .map(SmartBiFinanceData::getActualAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Sum cost rows matching ANY of the keywords on category, defending {@code .abs()} per legacy behavior. */
    private BigDecimal sumCostByKeywords(List<SmartBiFinanceData> rows, String[] keywords) {
        return rows.stream()
                .filter(r -> categoryMatches(r.getCategory(), keywords))
                .map(r -> r.getTotalCost() != null ? r.getTotalCost() : r.getActualAmount())
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumTotalCost(List<SmartBiFinanceData> rows) {
        return rows.stream()
                .map(r -> r.getTotalCost() != null ? r.getTotalCost() : r.getActualAmount())
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean categoryMatches(String category, String[] keywords) {
        if (category == null) return false;
        for (String kw : keywords) {
            if (category.contains(kw)) return true;
        }
        return false;
    }

    /** Returns {@code numerator / denominator * 100} (0-100 scale) or null. */
    private Double ratioPct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null
                || numerator.signum() == 0 || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, SCALE, ROUNDING)
                .multiply(HUNDRED)
                .doubleValue();
    }

    /** Returns {@code (current - prev) / prev} (0-1 scale) or null. */
    private Double changeRatio(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() <= 0) {
            return null;
        }
        return current.subtract(previous)
                .divide(previous, SCALE, ROUNDING)
                .doubleValue();
    }
}
