package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantPeakMonthGoldTool}.
 *
 * <p>Tests focus on the aggregation logic in {@code format()} — the key
 * business logic this tool owns. Gold client is mocked so tests are purely
 * in-process.
 *
 * <p>Test data: two months with known sums:
 * <ul>
 *   <li>2026-03 total: 11,575,403  (3 days: 4_000_000 + 3_575_403 + 4_000_000)</li>
 *   <li>2026-01 total: 11,046_513  (3 days: 3_500_000 + 4_046_513 + 3_500_000)</li>
 * </ul>
 * Expected peak month: "2026-03".
 */
@ExtendWith(MockitoExtension.class)
class RestaurantPeakMonthGoldToolTest {

    private static final String FACTORY_ID = "RES_3101_009";

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantPeakMonthGoldTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantPeakMonthGoldTool();
        injectGold(tool, goldClient);
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-PMG-01: metadata — toolName / description / schema")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_peak_month_gold");
        assertThat(tool.getDescription()).contains("峰值月份").contains("daily-trend");
        assertThat(tool.getParametersSchema()).containsKey("properties");
        assertThat(tool.getRequiredParameters()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // format() — aggregation logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-PMG-02: format() — 两月数据, 峰值月份 2026-03")
    void format_twoMonths_peakIsHigherMonth() {
        Map<String, Object> goldResult = buildTrendResult(List.of(
                point("2026-01-05", 3_500_000.0),
                point("2026-01-12", 4_046_513.0),
                point("2026-01-19", 3_500_000.0),
                point("2026-03-03", 4_000_000.0),
                point("2026-03-15", 3_575_403.0),
                point("2026-03-28", 4_000_000.0)
        ));

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("峰值月份", "2026-03");

        // 峰值营收 ≈ 11_575_403.0 (3 days summed)
        double peakRevenue = ((Number) result.get("峰值营收")).doubleValue();
        assertThat(peakRevenue).isCloseTo(11_575_403.0, org.assertj.core.data.Offset.offset(1.0));

        // 按月营收 list should have 2 entries, sorted desc → first is 2026-03
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byMonth = (List<Map<String, Object>>) result.get("按月营收");
        assertThat(byMonth).hasSize(2);
        assertThat(byMonth.get(0).get("月份")).isEqualTo("2026-03");
        assertThat(byMonth.get(1).get("月份")).isEqualTo("2026-01");

        // message should be present and contain the peak month
        assertThat(result).containsKey("message");
        assertThat(result.get("message").toString()).contains("2026-03");
    }

    @Test
    @DisplayName("UT-PMG-03: format() — 单月数据, 峰值即唯一月份")
    void format_singleMonth() {
        Map<String, Object> goldResult = buildTrendResult(List.of(
                point("2026-05-01", 1_000_000.0),
                point("2026-05-15", 2_000_000.0)
        ));

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("峰值月份", "2026-05");
        double peakRevenue = ((Number) result.get("峰值营收")).doubleValue();
        assertThat(peakRevenue).isCloseTo(3_000_000.0, org.assertj.core.data.Offset.offset(1.0));
    }

    // -------------------------------------------------------------------------
    // isEmpty()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-PMG-04: isEmpty() — empty points list → true")
    void isEmpty_emptyPoints() {
        assertThat(tool.isEmpty(buildTrendResult(List.of()))).isTrue();
    }

    @Test
    @DisplayName("UT-PMG-05: isEmpty() — missing points key → true")
    void isEmpty_missingKey() {
        assertThat(tool.isEmpty(new HashMap<>())).isTrue();
    }

    @Test
    @DisplayName("UT-PMG-06: isEmpty() — non-empty points → false")
    void isEmpty_nonEmpty() {
        assertThat(tool.isEmpty(buildTrendResult(List.of(point("2026-03-01", 1000.0))))).isFalse();
    }

    // -------------------------------------------------------------------------
    // doExecute() — integration via mock Gold client
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-PMG-07: doExecute() — Gold returns trend → peak month propagated")
    void doExecute_goldReturnsTrend_peakPropagated() throws Exception {
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of(
                        "min_date", "2026-01-01",
                        "max_date", "2026-03-31",
                        "day_count", 90
                ));
        when(goldClient.fetchDailyTrend(any(), any(), any()))
                .thenReturn(buildTrendResult(List.of(
                        point("2026-01-10", 5_000_000.0),
                        point("2026-03-10", 7_000_000.0)
                )));

        Map<String, Object> ctx = new HashMap<>();
        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), ctx);

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("峰值月份", "2026-03");
        assertThat(result.get("suggestedFollowups")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> followups =
                (List<Map<String, String>>) result.get("suggestedFollowups");
        assertThat(followups)
                .extracting(item -> item.get("label"))
                .containsExactly("拆解订单与客单价", "补充经营维度");
        assertThat(followups.get(0).get("question"))
                .isEqualTo("对比峰值月和次高月的订单量与客单价");
        assertThat(followups.get(1).get("question"))
                .contains("2026-03峰值月")
                .contains("经营构成与可能原因");
    }

    @Test
    @DisplayName("UT-PMG-08: peak-month query owns the full-range comparison and never delegates to missing-time clarification")
    void doExecute_peakMonthQuestionDoesNotDelegate() throws Exception {
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of(
                        "min_date", "2026-01-01",
                        "max_date", "2026-03-31",
                        "day_count", 90
                ));
        when(goldClient.fetchDailyTrend(
                FACTORY_ID, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")))
                .thenReturn(buildTrendResult(List.of(
                        point("2026-01-10", 5_000_000.0),
                        point("2026-03-10", 7_000_000.0)
                )));

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                new HashMap<>(Map.of("userInput", "哪个月营收最高，为什么")),
                new HashMap<>());

        assertThat(result).containsEntry("峰值月份", "2026-03");
        assertThat(result.get("message").toString())
                .contains("当前结果使用的是按日营收数据")
                .contains("不能仅凭营收序列断定原因")
                .contains("缺少的维度应保持为空");
        assertThat(result.get("suggestedFollowups")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> followups =
                (List<Map<String, String>>) result.get("suggestedFollowups");
        Map<String, String> dimensionsFollowup = followups.stream()
                .filter(item -> "补充经营维度".equals(item.get("label")))
                .findFirst()
                .orElseThrow();
        assertThat(dimensionsFollowup.get("question"))
                .contains("2026-03峰值月")
                .contains("经营构成与可能原因")
                .doesNotContain("分析峰值月原因");
        verify(goldClient, never()).fetchTieredIntentAnswer(
                eq(FACTORY_ID), any(), eq("restaurant_peak_month_gold"));

        assertThat(tool.shouldDelegateToTieredIntent("营收最高的是哪个月份")).isFalse();
        assertThat(tool.shouldDelegateToTieredIntent("月度营收峰值在哪里")).isFalse();
        assertThat(tool.shouldDelegateToTieredIntent("这两个月生意怎么样")).isTrue();
    }

    @Test
    @DisplayName("UT-PMG-09: peak-month order/ticket follow-up is deterministic and uses weighted monthly metrics")
    void doExecute_orderTicketFollowupUsesDailyTrendWithoutLlm() throws Exception {
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of(
                        "min_date", "2026-01-01",
                        "max_date", "2026-03-31",
                        "day_count", 90
                ));
        when(goldClient.fetchDailyTrend(
                FACTORY_ID, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")))
                .thenReturn(buildTrendResult(List.of(
                        point("2026-01-10", 5_000_000.0, 200),
                        point("2026-03-10", 7_000_000.0, 300)
                )));

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                new HashMap<>(Map.of(
                        "userInput", "对比峰值月和次高月的订单量与客单价")),
                new HashMap<>());

        assertThat(result)
                .containsEntry("峰值月份", "2026-03")
                .containsEntry("次高月份", "2026-01");
        assertThat(result.get("message").toString())
                .contains("峰值月与次高月的订单量、客单价对比")
                .contains("峰值月 2026-03：营收 700.0万，订单 300 单，客单价 ¥23333.33")
                .contains("次高月 2026-01：营收 500.0万，订单 200 单，客单价 ¥25000.00")
                .contains("峰值月订单量多 100 单，客单价低 ¥1666.67")
                .contains("不能单独证明营收差异的原因");
        verify(goldClient, never()).fetchTieredIntentAnswer(
                eq(FACTORY_ID), any(), eq("restaurant_peak_month_gold"));
        assertThat(tool.shouldDelegateToTieredIntent(
                "对比峰值月和次高月的订单量与客单价")).isFalse();
    }

    @Test
    @DisplayName("UT-PMG-10: synonym follow-up routes deterministically and partial order data stays missing")
    void doExecute_synonymFollowupDoesNotFabricatePartialMonthlyOrders() throws Exception {
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of(
                        "min_date", "2026-01-01",
                        "max_date", "2026-03-31",
                        "day_count", 90
                ));
        Map<String, Object> missingOrderPoint = new HashMap<>();
        missingOrderPoint.put("date", "2026-03-11");
        missingOrderPoint.put("revenue", 1_000_000.0);
        when(goldClient.fetchDailyTrend(
                FACTORY_ID, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")))
                .thenReturn(buildTrendResult(List.of(
                        point("2026-01-10", 5_000_000.0, 200),
                        point("2026-03-10", 7_000_000.0, 300),
                        missingOrderPoint
                )));

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                new HashMap<>(Map.of(
                        "userInput", "比较营收峰值月与第二高月份的单量、平均客单")),
                new HashMap<>());

        assertThat(result.get("message").toString())
                .contains("峰值月 2026-03：营收 800.0万，订单量/客单价缺失")
                .contains("缺失值不会按 0 处理");
        verify(goldClient, never()).fetchTieredIntentAnswer(
                eq(FACTORY_ID), any(), eq("restaurant_peak_month_gold"));
        assertThat(tool.shouldDelegateToTieredIntent(
                "比较营收峰值月与第二高月份的单量、平均客单")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildTrendResult(List<Map<String, Object>> points) {
        Map<String, Object> m = new HashMap<>();
        m.put("factory_id", FACTORY_ID);
        m.put("start_date", "2026-01-01");
        m.put("end_date", "2026-03-31");
        m.put("points", new ArrayList<>(points));
        return m;
    }

    private static Map<String, Object> point(String date, double revenue) {
        return point(date, revenue, 100);
    }

    private static Map<String, Object> point(String date, double revenue, int billCount) {
        Map<String, Object> p = new HashMap<>();
        p.put("date", date);
        p.put("revenue", revenue);
        p.put("bill_count", billCount);
        p.put("avg_bill_value", revenue / billCount);
        return p;
    }

    /** Inject the GoldFinanceClient mock into the protected {@code gold} field. */
    private static void injectGold(GoldBackedRestaurantTool tool, GoldFinanceClient client)
            throws Exception {
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, client);
    }
}
