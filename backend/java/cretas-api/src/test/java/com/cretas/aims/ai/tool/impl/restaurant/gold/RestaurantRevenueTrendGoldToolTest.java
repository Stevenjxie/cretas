package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantRevenueTrendGoldTool}.
 *
 * <p>Tests focus on the trend aggregation logic in {@code format()} — peak/trough
 * detection, first→last 环比 direction, latest-month 环比, and weekday/weekend split.
 * Gold client is mocked so tests are purely in-process.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantRevenueTrendGoldToolTest {

    private static final String FACTORY_ID = "RES_3101_009";

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantRevenueTrendGoldTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantRevenueTrendGoldTool();
        injectGold(tool, goldClient);
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-RTG-01: metadata — toolName / description / schema")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_revenue_trend_gold");
        assertThat(tool.getDescription()).contains("营收趋势").contains("trend_bundle");
        assertThat(tool.getParametersSchema()).containsKey("properties");
        assertThat(tool.getRequiredParameters()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // format() — trend aggregation logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-RTG-02: format() — ascending months, peak/trough + 上升环比")
    void format_trendUp() {
        Map<String, Object> goldResult = buildBundle(
                List.of(
                        month("2025-01", 1_000_000.0),
                        month("2025-02", 1_500_000.0),
                        month("2025-03", 3_000_000.0)
                ),
                80_000.0, 120_000.0);

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("峰值月份", "2025-03");
        assertThat(result).containsEntry("最低月份", "2025-01");
        assertThat(result).containsEntry("整体环比方向", "上升");

        // first→last: (3_000_000 - 1_000_000) / 1_000_000 = +200.0%
        double momPct = ((Number) result.get("整体环比百分比")).doubleValue();
        assertThat(momPct).isCloseTo(200.0, org.assertj.core.data.Offset.offset(0.1));

        // latest month vs previous: (3_000_000 - 1_500_000) / 1_500_000 = +100.0%
        assertThat(result).containsEntry("最新月环比方向", "上升");
        double latestPct = ((Number) result.get("最新月环比百分比")).doubleValue();
        assertThat(latestPct).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byMonth = (List<Map<String, Object>>) result.get("月度营收趋势");
        assertThat(byMonth).hasSize(3);
        assertThat(byMonth.get(0).get("月份")).isEqualTo("2025-01");
        assertThat(byMonth.get(2).get("月份")).isEqualTo("2025-03");

        // chartConfig is a line chart with months ascending (not reversed).
        assertThat(result).containsKey("chartConfig");
        @SuppressWarnings("unchecked")
        Map<String, Object> chart = (Map<String, Object>) result.get("chartConfig");
        assertThat(chart).containsEntry("type", "line");
        assertThat(result.get("message").toString()).contains("2025-03").contains("上升");

        // weekend (120k) > weekday (80k) → 周末日均更高
        assertThat(result.get("message").toString()).contains("周末日均更高");
    }

    @Test
    @DisplayName("UT-RTG-03: format() — 下降环比 detected")
    void format_trendDown() {
        Map<String, Object> goldResult = buildBundle(
                List.of(
                        month("2025-05", 5_000_000.0),
                        month("2025-06", 2_000_000.0)
                ),
                0.0, 0.0);

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("整体环比方向", "下降");
        assertThat(result).containsEntry("峰值月份", "2025-05");
        assertThat(result).containsEntry("最低月份", "2025-06");
        double momPct = ((Number) result.get("整体环比百分比")).doubleValue();
        assertThat(momPct).isCloseTo(-60.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("UT-RTG-04: format() — single month, no 环比 direction beyond 持平")
    void format_singleMonth() {
        Map<String, Object> goldResult = buildBundle(
                List.of(month("2026-01", 2_500_000.0)), 0.0, 0.0);

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("峰值月份", "2026-01");
        assertThat(result).containsEntry("整体环比方向", "持平");
        // single month → no latest-month 环比 key
        assertThat(result).doesNotContainKey("最新月环比方向");
    }

    // -------------------------------------------------------------------------
    // isEmpty()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-RTG-05: isEmpty() — empty monthlyTrend → true")
    void isEmpty_empty() {
        assertThat(tool.isEmpty(buildBundle(List.of(), 0.0, 0.0))).isTrue();
    }

    @Test
    @DisplayName("UT-RTG-06: isEmpty() — missing monthlyTrend key → true")
    void isEmpty_missingKey() {
        assertThat(tool.isEmpty(new HashMap<>())).isTrue();
    }

    @Test
    @DisplayName("UT-RTG-07: isEmpty() — non-empty monthlyTrend → false")
    void isEmpty_nonEmpty() {
        assertThat(tool.isEmpty(buildBundle(List.of(month("2025-01", 100.0)), 0.0, 0.0))).isFalse();
    }

    // -------------------------------------------------------------------------
    // doExecute() — integration via mock Gold client (all-history path)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-RTG-08: doExecute() — Gold returns bundle → trend propagated")
    void doExecute_goldReturnsBundle() throws Exception {
        // No month param + factory has data range → resolveWindow uses the full span,
        // then queryGold calls fetchTrendBundle with that window.
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of(
                        "min_date", "2025-01-01",
                        "max_date", "2026-03-31",
                        "day_count", 400
                ));
        when(goldClient.fetchTrendBundle(any(), any(), any()))
                .thenReturn(buildBundle(List.of(
                        month("2025-12", 4_000_000.0),
                        month("2026-01", 6_000_000.0)
                ), 100_000.0, 90_000.0));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("峰值月份", "2026-01");
        assertThat(result).containsEntry("整体环比方向", "上升");
    }

    @Test
    @DisplayName("UT-RTG-09: doExecute() — Gold empty bundle → fool-proof message, no dead-end")
    void doExecute_emptyBundle() throws Exception {
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of("min_date", "2025-01-01", "max_date", "2025-12-31", "day_count", 1));
        when(goldClient.fetchTrendBundle(any(), any(), any()))
                .thenReturn(buildBundle(List.of(), 0.0, 0.0));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("dataAvailable", false);
        assertThat(result.get("message").toString()).contains("营收趋势");
        assertThat(result).containsKey("actionHint");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildBundle(
            List<Map<String, Object>> monthly, double weekdayAvg, double weekendAvg) {
        Map<String, Object> ww = new HashMap<>();
        ww.put("weekdayAvg", weekdayAvg);
        ww.put("weekendAvg", weekendAvg);
        ww.put("weekdayDays", 5);
        ww.put("weekendDays", 2);

        Map<String, Object> m = new HashMap<>();
        m.put("factory_id", FACTORY_ID);
        m.put("start_date", null);
        m.put("end_date", null);
        m.put("dailyTrend", new ArrayList<>());
        m.put("weekdayWeekend", ww);
        m.put("monthlyTrend", new ArrayList<>(monthly));
        return m;
    }

    private static Map<String, Object> month(String ym, double revenue) {
        Map<String, Object> e = new HashMap<>();
        e.put("month", ym);
        e.put("revenue", revenue);
        return e;
    }

    /** Inject the GoldFinanceClient mock into the protected {@code gold} field. */
    private static void injectGold(GoldBackedRestaurantTool tool, GoldFinanceClient client)
            throws Exception {
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, client);
    }
}
