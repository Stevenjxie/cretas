package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 12 Phase B — fetch restaurant shrinkage rows from cretas_prod_db.
 *
 * <p>Pre-Sprint 12: {@link RestaurantShrinkageAnalysisTool} inherited default
 * {@code buildSectionParams()} which passes nothing → Python {@code shrinkage_analysis}
 * section returns SKIPPED ("未提供 shrinkage_rows") → Composite Tool reports
 * "档口损溢 数据不可用" for every customer query.
 *
 * <p>Sprint 12 Phase B: this Fetcher joins {@code wastage_records} (cretas business
 * table) ↔ {@code raw_material_types} (catalog), groups APPROVED wastage by
 * ingredient category (e.g. 水产/肉类/蔬菜/豆制品), and emits the list shape
 * Python expects:
 * <pre>
 *   [{"department": "水产", "standardCost": 124.50, "actualCost": 283.50},
 *    {"department": "肉类", "standardCost": 124.50, "actualCost": 148.50},
 *    {"department": "蔬菜", "standardCost": 124.50, "actualCost": 50.00},
 *    {"department": "豆制品", "standardCost": 124.50, "actualCost": 16.00}]
 * </pre>
 *
 * <p>Algorithm (v1 — uniform mean baseline):
 * <ol>
 *   <li>Group APPROVED wastage in [start, end] by {@code raw_material_types.category}
 *   <li>Skip if fewer than 2 categories (single-category comparison is meaningless)
 *   <li>{@code standardCost = totalWastage / categoryCount} — uniform baseline; an
 *       offender is a category whose share exceeds its equal-split portion
 *   <li>{@code actualCost = perCategoryWastageSum}
 * </ol>
 *
 * <p>This is INTENTIONALLY a simple v1 because cretas_prod_db lacks per-category
 * BOM-derived consumption tracking (POS sales live in {@code smartbi_prod_db}
 * which the Java backend does not have a configured DataSource for — see
 * {@code .claude/rules/concurrent-edit-safety.md} ↔ {@code feedback_smartbi_repo_uses_primary_datasource.md}).
 * A future enhancement (Sprint 13+) can swap the baseline for POS×recipe-derived
 * standard cost once cretas-side POS-data sync ships.
 *
 * <p>Returns {@link Optional#empty()} when the period has &lt;2 wastage categories
 * — callers must pass nothing to Python (which then emits its canonical
 * "未提供 shrinkage_rows" skip message), keeping the no-data UX consistent.
 *
 * @since 2026-05-29 (Sprint 12 Phase B)
 */
@Slf4j
@Component
public class RestaurantShrinkageDataFetcher {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Need ≥2 categories for a comparison; otherwise "shrinkage" has no peer. */
    static final int MIN_CATEGORIES_FOR_ANALYSIS = 2;

    /** "YYYY-MM" or "YYYY年M月" parser for {@code month} param — mirrors
     *  {@link RestaurantFinancialMetricsFetcher#resolveMonthRange}. */
    private static final Pattern MONTH_PATTERN = Pattern.compile(
        "^\\s*(\\d{4})\\s*[年\\-/]\\s*(\\d{1,2})\\s*月?\\s*$"
    );

    private final WastageRecordRepository wastageRepository;

    @Autowired
    public RestaurantShrinkageDataFetcher(WastageRecordRepository wastageRepository) {
        this.wastageRepository = wastageRepository;
    }

    /**
     * Build the {@code shrinkage_rows} list for Python's {@code shrinkage_analysis} section.
     *
     * @param factoryId Restaurant factory id (e.g. {@code RES_3101_009})
     * @param monthRaw  Optional month label ("上月" / "本月" / "2026-04" /
     *                  "2026年4月" / null → "上月")
     * @return Optional containing list of camelCase row dicts; empty if &lt;2
     *         ingredient categories have APPROVED wastage in the period.
     */
    public Optional<List<Map<String, Object>>> fetch(String factoryId, String monthRaw) {
        LocalDate[] range = resolveMonthRange(monthRaw);
        LocalDate start = range[0];
        LocalDate end = range[1];

        List<Object[]> stats = wastageRepository.getStatisticsByIngredientCategory(
                factoryId, start, end);

        if (stats == null || stats.size() < MIN_CATEGORIES_FOR_ANALYSIS) {
            log.info("RestaurantShrinkageDataFetcher: insufficient categories for factory={} range={}..{} (got {})",
                    factoryId, start, end, stats == null ? 0 : stats.size());
            return Optional.empty();
        }

        BigDecimal totalCost = stats.stream()
                .map(row -> toBigDecimal(row[2]))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCost.signum() <= 0) {
            log.info("RestaurantShrinkageDataFetcher: total wastage cost = 0 for factory={} range={}..{}",
                    factoryId, start, end);
            return Optional.empty();
        }

        BigDecimal standardCost = totalCost.divide(
                BigDecimal.valueOf(stats.size()), SCALE, ROUNDING);

        List<Map<String, Object>> rows = new ArrayList<>(stats.size());
        for (Object[] row : stats) {
            String category = Objects.toString(row[0], "未分类");
            BigDecimal actualCost = toBigDecimal(row[2]);
            if (actualCost == null) continue;

            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("department", category);
            rowMap.put("standardCost", standardCost.doubleValue());
            rowMap.put("actualCost", actualCost.setScale(SCALE, ROUNDING).doubleValue());
            rows.add(rowMap);
        }

        log.info("RestaurantShrinkageDataFetcher: factory={} range={}..{} categories={} total={} mean={}",
                factoryId, start, end, rows.size(), totalCost, standardCost);

        return Optional.of(rows);
    }

    // ─── Internals ──────────────────────────────────────────────────────

    /**
     * Resolve "上月"/"本月"/"YYYY-MM"/"YYYY年M月"/null → [monthStart, monthEnd].
     * Mirrors {@link RestaurantFinancialMetricsFetcher#resolveMonthRange} so the
     * two Fetchers cover the SAME period when invoked from one Composite call.
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

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
