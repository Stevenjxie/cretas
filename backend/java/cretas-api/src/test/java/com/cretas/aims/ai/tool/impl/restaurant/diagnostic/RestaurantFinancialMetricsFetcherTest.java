package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.entity.smartbi.SmartBiFinanceData;
import com.cretas.aims.entity.smartbi.enums.RecordType;
import com.cretas.aims.repository.smartbi.SmartBiFinanceDataRepository;
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
 * Unit tests for {@link RestaurantFinancialMetricsFetcher}.
 *
 * <p>Covers Sprint 11.5 Phase 1 wire-up — verifies the camelCase shape
 * mirrors Python's {@code FinancialMetrics.to_dict()} contract so the
 * {@code store_pnl_one_pager} section can consume directly.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantFinancialMetricsFetcherTest {

    private static final String FACTORY = "RES_3101_009";

    @Mock
    private SmartBiFinanceDataRepository repo;

    private RestaurantFinancialMetricsFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new RestaurantFinancialMetricsFetcher(repo);
    }

    // ─── Helpers ────────────────────────────────────────────

    private SmartBiFinanceData revenue(String category, double amount) {
        return SmartBiFinanceData.builder()
                .factoryId(FACTORY)
                .uploadId(1L)
                .recordDate(LocalDate.now())
                .recordType(RecordType.REVENUE)
                .category(category)
                .actualAmount(BigDecimal.valueOf(amount))
                .build();
    }

    private SmartBiFinanceData cost(String category, double amount) {
        return SmartBiFinanceData.builder()
                .factoryId(FACTORY)
                .uploadId(1L)
                .recordDate(LocalDate.now())
                .recordType(RecordType.COST)
                .category(category)
                .totalCost(BigDecimal.valueOf(amount))
                .build();
    }

    private SmartBiFinanceData costWithUpload(String category, double amount, long uploadId) {
        return SmartBiFinanceData.builder()
                .factoryId(FACTORY)
                .uploadId(uploadId)
                .recordDate(LocalDate.now())
                .recordType(RecordType.COST)
                .category(category)
                .totalCost(BigDecimal.valueOf(amount))
                .build();
    }

    // ─── Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("happy path — full P&L with current + previous period yields camelCase metrics")
    void fetch_happyPath_returnsFullCamelCaseDict() {
        // current period (上月) REVENUE/COST
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.REVENUE), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDate s = invocation.getArgument(2);
                    boolean isCurrent = s.equals(s.withDayOfMonth(1))
                            && s.equals(LocalDate.now().minusMonths(1).withDayOfMonth(1));
                    if (isCurrent) {
                        return List.of(
                                revenue("营业收入", 731048.0),
                                revenue("净利润", -49724.0)
                        );
                    }
                    return List.of(revenue("营业收入", 1390000.0));
                });
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.COST), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDate s = invocation.getArgument(2);
                    boolean isCurrent = s.equals(LocalDate.now().minusMonths(1).withDayOfMonth(1));
                    if (isCurrent) {
                        return List.of(
                                cost("食材成本", 335213.0),
                                cost("人工成本", 237660.0),
                                cost("房租", 57328.0),
                                cost("水电杂费", 12000.0)
                        );
                    }
                    return List.of(
                            cost("食材成本", 525000.0),
                            cost("人工成本", 250000.0)
                    );
                });

        Optional<Map<String, Object>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isPresent();
        Map<String, Object> m = result.get();

        // Core amounts (camelCase per FinancialMetrics.to_dict)
        assertThat((Double) m.get("revenue")).isEqualTo(731048.0);
        assertThat((Double) m.get("foodCost")).isEqualTo(335213.0);
        assertThat((Double) m.get("laborCost")).isEqualTo(237660.0);
        assertThat((Double) m.get("rent")).isEqualTo(57328.0);
        assertThat((Double) m.get("otherCost")).isEqualTo(12000.0); // totalCost - food - labor - rent
        assertThat((Double) m.get("netProfit")).isEqualTo(-49724.0);

        // Ratios in 0-100 scale (Python contract)
        assertThat((Double) m.get("foodCostRatio")).isCloseTo(45.85, offset(0.05));
        assertThat((Double) m.get("laborCostRatio")).isCloseTo(32.51, offset(0.05));
        assertThat((Double) m.get("rentRatio")).isCloseTo(7.84, offset(0.05));
        assertThat((Double) m.get("restaurantNetMargin")).isCloseTo(-6.8, offset(0.1));

        // Change ratios (0-1 scale) — revenue down 47%
        Double revChange = (Double) m.get("revenueChangePct");
        assertThat(revChange).isNotNull();
        assertThat(revChange).isLessThan(-0.4);  // ~ -0.474

        // cost_rigidity = laborChange / revenueChange — only when revenue drop > 5%
        Double rigidity = (Double) m.get("costRigidity");
        assertThat(rigidity).isNotNull();
        // labor change ~ (237660-250000)/250000 = -0.0494
        // revenue change ~ -0.474
        // rigidity ~ 0.104
        assertThat(rigidity).isBetween(0.05, 0.2);

        // Dual margin (folded == unfolded without discount data)
        assertThat((Double) m.get("grossMarginFolded")).isCloseTo(54.15, offset(0.1));
        assertThat((Double) m.get("grossMarginUnfolded")).isCloseTo(54.15, offset(0.1));
        assertThat((Double) m.get("grossRevenue")).isEqualTo(731048.0);
    }

    @Test
    @DisplayName("no data — returns Optional.empty so Python falls through to skip")
    void fetch_noData_returnsEmpty() {
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), any(), any(), any()))
                .thenReturn(List.of());

        Optional<Map<String, Object>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("absent 净利 category — derive netProfit from revenue - totalCost")
    void fetch_noNetProfitCategory_derivesFromRevenueMinusCost() {
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.REVENUE), any(), any()))
                .thenReturn(List.of(revenue("营业收入", 100000.0)));
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.COST), any(), any()))
                .thenReturn(List.of(
                        cost("食材成本", 40000.0),
                        cost("人工成本", 30000.0)
                ));

        Optional<Map<String, Object>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isPresent();
        // netProfit = 100000 - 40000 - 30000 = 30000
        assertThat((Double) result.get().get("netProfit")).isEqualTo(30000.0);
        // restaurantNetMargin = 30000/100000 * 100 = 30
        assertThat((Double) result.get().get("restaurantNetMargin")).isCloseTo(30.0, offset(0.05));
    }

    @Test
    @DisplayName("cost rigidity skipped when revenue change too small (>= -5%)")
    void fetch_smallRevenueChange_skipsCostRigidity() {
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.REVENUE), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDate s = invocation.getArgument(2);
                    boolean isCurrent = s.equals(LocalDate.now().minusMonths(1).withDayOfMonth(1));
                    return isCurrent
                            ? List.of(revenue("营业收入", 100000.0))
                            : List.of(revenue("营业收入", 103000.0));  // -2.9% only
                });
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.COST), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDate s = invocation.getArgument(2);
                    boolean isCurrent = s.equals(LocalDate.now().minusMonths(1).withDayOfMonth(1));
                    return isCurrent
                            ? List.of(cost("人工成本", 30000.0))
                            : List.of(cost("人工成本", 25000.0));
                });

        Optional<Map<String, Object>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isPresent();
        assertThat(result.get().get("costRigidity")).isNull();
        // revenueChangePct should still be present
        assertThat(result.get().get("revenueChangePct")).isNotNull();
    }

    @Test
    @DisplayName("multi-upload — only latest upload counted (no double-aggregation)")
    void fetch_multiUpload_filtersToLatest() {
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.REVENUE), any(), any()))
                .thenReturn(List.of(revenue("营业收入", 100000.0)));
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.COST), any(), any()))
                .thenReturn(List.of(
                        // old upload should be filtered out
                        costWithUpload("食材成本", 999999.0, 1L),
                        costWithUpload("食材成本", 40000.0, 5L),
                        costWithUpload("人工成本", 30000.0, 5L)
                ));

        Optional<Map<String, Object>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isPresent();
        assertThat((Double) result.get().get("foodCost")).isEqualTo(40000.0);
        assertThat((Double) result.get().get("laborCost")).isEqualTo(30000.0);
    }

    @Test
    @DisplayName("negative cost values — taken as absolute (legacy Bug A defense)")
    void fetch_negativeCost_takenAsAbs() {
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.REVENUE), any(), any()))
                .thenReturn(List.of(revenue("营业收入", 100000.0)));
        when(repo.findByFactoryIdAndRecordTypeAndRecordDateBetween(
                eq(FACTORY), eq(RecordType.COST), any(), any()))
                .thenReturn(List.of(cost("食材成本", -40000.0)));

        Optional<Map<String, Object>> result = fetcher.fetch(FACTORY, "上月");

        assertThat(result).isPresent();
        assertThat((Double) result.get().get("foodCost")).isEqualTo(40000.0);
    }

    @Test
    @DisplayName("month phrase — '本月' picks current month")
    void resolveMonthRange_thisMonth() {
        LocalDate[] range = fetcher.resolveMonthRange("本月");
        LocalDate today = LocalDate.now();
        assertThat(range[0]).isEqualTo(today.withDayOfMonth(1));
        assertThat(range[1]).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()));
    }

    @Test
    @DisplayName("month phrase — '上月' picks previous month")
    void resolveMonthRange_lastMonth() {
        LocalDate[] range = fetcher.resolveMonthRange("上月");
        LocalDate prev = LocalDate.now().minusMonths(1);
        assertThat(range[0]).isEqualTo(prev.withDayOfMonth(1));
        assertThat(range[1]).isEqualTo(prev.withDayOfMonth(prev.lengthOfMonth()));
    }

    @Test
    @DisplayName("month phrase — 'YYYY-MM' parses explicit month")
    void resolveMonthRange_explicitYearMonth() {
        LocalDate[] range = fetcher.resolveMonthRange("2026-04");
        assertThat(range[0]).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(range[1]).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("month phrase — 'YYYY年M月' Chinese form parses")
    void resolveMonthRange_chineseYearMonth() {
        LocalDate[] range = fetcher.resolveMonthRange("2025年12月");
        assertThat(range[0]).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(range[1]).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("month phrase — null / empty / unparseable defaults to '上月'")
    void resolveMonthRange_invalidDefaultsToLastMonth() {
        LocalDate prev = LocalDate.now().minusMonths(1);
        LocalDate expectedStart = prev.withDayOfMonth(1);
        LocalDate expectedEnd = prev.withDayOfMonth(prev.lengthOfMonth());

        for (String input : new String[]{null, "", "  ", "随便写的字"}) {
            LocalDate[] r = fetcher.resolveMonthRange(input);
            assertThat(r[0]).as("input: %s", input).isEqualTo(expectedStart);
            assertThat(r[1]).as("input: %s", input).isEqualTo(expectedEnd);
        }
    }

    @Test
    @DisplayName("formatPeriod — returns YYYY-MM label")
    void formatPeriod_returnsIsoMonth() {
        String label = fetcher.formatPeriod("2026-04");
        assertThat(label).isEqualTo("2026-04");
    }
}
