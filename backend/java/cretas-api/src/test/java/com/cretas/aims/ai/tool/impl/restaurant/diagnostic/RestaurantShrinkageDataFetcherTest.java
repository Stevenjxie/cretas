package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantShrinkageDataFetcher} (Sprint 12 Phase B).
 *
 * <p>Verifies the shape returned matches Python {@code shrinkage_analysis}
 * section contract: {@code [{department, standardCost, actualCost}, ...]}.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantShrinkageDataFetcherTest {

    private static final String FACTORY = "RES_3101_009";

    @Mock
    private WastageRecordRepository wastageRepository;

    private RestaurantShrinkageDataFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new RestaurantShrinkageDataFetcher(wastageRepository);
    }

    /** Helper: build the JPA-aggregate Object[] tuple shape (category, count, sumCost). */
    private Object[] tuple(String category, long count, double sumCost) {
        return new Object[]{category, count, BigDecimal.valueOf(sumCost)};
    }

    // ─── Happy path ────────────────────────────────────────────

    @Test
    @DisplayName("Real RES_3101_009 prod data — 4 categories with offenders")
    void fetch_realProdLikeDataFourCategories() {
        // Mirror prod state 2026-05-29: 6 wastage events across 4 ingredient categories
        when(wastageRepository.getStatisticsByIngredientCategory(eq(FACTORY), any(), any()))
                .thenReturn(List.of(
                        tuple("水产", 2, 283.50),
                        tuple("肉类", 2, 148.50),
                        tuple("蔬菜", 1, 50.00),
                        tuple("豆制品", 1, 16.00)
                ));

        Optional<List<Map<String, Object>>> result = fetcher.fetch(FACTORY, "2026-04");

        assertThat(result).isPresent();
        List<Map<String, Object>> rows = result.get();
        assertThat(rows).hasSize(4);

        // Mean baseline = (283.50 + 148.50 + 50 + 16) / 4 = 498.00 / 4 = 124.50
        for (Map<String, Object> row : rows) {
            assertThat(row.get("department")).isInstanceOf(String.class);
            assertThat(row.get("standardCost")).isEqualTo(124.50);
            assertThat(row.get("actualCost")).isInstanceOf(Double.class);
        }

        // Ordering preserved (DESC by sumCost from repo)
        assertThat(rows.get(0).get("department")).isEqualTo("水产");
        assertThat(rows.get(0).get("actualCost")).isEqualTo(283.50);
        assertThat(rows.get(3).get("department")).isEqualTo("豆制品");
        assertThat(rows.get(3).get("actualCost")).isEqualTo(16.00);
    }

    @Test
    @DisplayName("Multi-category — mean baseline computed correctly")
    void fetch_meanBaselineComputation() {
        when(wastageRepository.getStatisticsByIngredientCategory(eq(FACTORY), any(), any()))
                .thenReturn(List.of(
                        tuple("热菜", 5, 5000.00),
                        tuple("冷菜", 3, 2000.00),
                        tuple("烧烤", 4, 3000.00)
                ));

        Optional<List<Map<String, Object>>> result = fetcher.fetch(FACTORY, "2026-04");

        assertThat(result).isPresent();
        // Mean = (5000 + 2000 + 3000) / 3 = 3333.33
        for (Map<String, Object> row : result.get()) {
            assertThat((Double) row.get("standardCost")).isEqualTo(3333.33, offset(0.01));
        }
    }

    // ─── Skip paths ────────────────────────────────────────────

    @Test
    @DisplayName("Empty repo result → Optional.empty (Python returns SKIPPED)")
    void fetch_emptyResultReturnsEmpty() {
        when(wastageRepository.getStatisticsByIngredientCategory(eq(FACTORY), any(), any()))
                .thenReturn(List.of());

        Optional<List<Map<String, Object>>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Single category → Optional.empty (need ≥2 for peer comparison)")
    void fetch_singleCategoryReturnsEmpty() {
        // List.<Object[]>of(...) forces type — single-arg List.of(Object[]) triggers varargs unpacking.
        when(wastageRepository.getStatisticsByIngredientCategory(eq(FACTORY), any(), any()))
                .thenReturn(List.<Object[]>of(tuple("水产", 3, 500.00)));

        Optional<List<Map<String, Object>>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("All-zero costs → Optional.empty (no signal to analyze)")
    void fetch_zeroCostsReturnsEmpty() {
        when(wastageRepository.getStatisticsByIngredientCategory(eq(FACTORY), any(), any()))
                .thenReturn(List.of(
                        tuple("水产", 0, 0.00),
                        tuple("肉类", 0, 0.00)
                ));

        Optional<List<Map<String, Object>>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Null repo result → Optional.empty (defensive)")
    void fetch_nullResultReturnsEmpty() {
        when(wastageRepository.getStatisticsByIngredientCategory(eq(FACTORY), any(), any()))
                .thenReturn((List<Object[]>) null);

        Optional<List<Map<String, Object>>> result = fetcher.fetch(FACTORY, null);

        assertThat(result).isEmpty();
    }

    // ─── Month parsing ────────────────────────────────────────────

    @Test
    @DisplayName("resolveMonthRange — '上月' returns previous calendar month [1st, last]")
    void resolveMonthRange_shangYueIsLastMonth() {
        LocalDate[] range = fetcher.resolveMonthRange("上月");
        LocalDate today = LocalDate.now();
        LocalDate expectedFirst = today.minusMonths(1).withDayOfMonth(1);
        LocalDate expectedLast = expectedFirst.withDayOfMonth(expectedFirst.lengthOfMonth());
        assertThat(range[0]).isEqualTo(expectedFirst);
        assertThat(range[1]).isEqualTo(expectedLast);
    }

    @Test
    @DisplayName("resolveMonthRange — null defaults to last month")
    void resolveMonthRange_nullDefaultsToLastMonth() {
        LocalDate[] range = fetcher.resolveMonthRange(null);
        LocalDate today = LocalDate.now();
        LocalDate expectedFirst = today.minusMonths(1).withDayOfMonth(1);
        assertThat(range[0]).isEqualTo(expectedFirst);
    }

    @Test
    @DisplayName("resolveMonthRange — 'YYYY-MM' format parsed correctly")
    void resolveMonthRange_yyyyMmFormat() {
        LocalDate[] range = fetcher.resolveMonthRange("2025-12");
        assertThat(range[0]).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(range[1]).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("resolveMonthRange — 'YYYY年M月' Chinese format parsed")
    void resolveMonthRange_yyyyYueMmYueFormat() {
        LocalDate[] range = fetcher.resolveMonthRange("2025年12月");
        assertThat(range[0]).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(range[1]).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("resolveMonthRange — malformed input falls back to last month")
    void resolveMonthRange_malformedFallback() {
        LocalDate[] range = fetcher.resolveMonthRange("garbage-input");
        LocalDate today = LocalDate.now();
        LocalDate expectedFirst = today.minusMonths(1).withDayOfMonth(1);
        assertThat(range[0]).isEqualTo(expectedFirst);
    }

    @Test
    @DisplayName("resolveMonthRange — month boundary 2-month with 28/29/30/31 days")
    void resolveMonthRange_monthBoundaries() {
        // Feb non-leap: 28
        LocalDate[] feb2025 = fetcher.resolveMonthRange("2025-02");
        assertThat(feb2025[1]).isEqualTo(LocalDate.of(2025, 2, 28));
        // April: 30
        LocalDate[] apr2026 = fetcher.resolveMonthRange("2026-04");
        assertThat(apr2026[1]).isEqualTo(LocalDate.of(2026, 4, 30));
        // December: 31
        LocalDate[] dec2025 = fetcher.resolveMonthRange("2025-12");
        assertThat(dec2025[1]).isEqualTo(LocalDate.of(2025, 12, 31));
    }
}
