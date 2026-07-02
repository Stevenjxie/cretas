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
 * Unit tests for {@link RestaurantWeekdayWeekendGoldTool}.
 *
 * <p>Tests focus on the weekday/weekend classification + aggregation logic in
 * {@code format()}. Calendar verification:
 * <ul>
 *   <li>2026-03-07 = Saturday (weekend)</li>
 *   <li>2026-03-08 = Sunday   (weekend)</li>
 *   <li>2026-03-09 = Monday   (weekday)</li>
 *   <li>2026-03-10 = Tuesday  (weekday)</li>
 *   <li>2026-03-11 = Wednesday(weekday)</li>
 *   <li>2026-03-14 = Saturday (weekend)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RestaurantWeekdayWeekendGoldToolTest {

    private static final String FACTORY_ID = "RES_3101_009";

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantWeekdayWeekendGoldTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantWeekdayWeekendGoldTool();
        injectGold(tool, goldClient);
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-WWG-01: metadata — toolName / description / schema")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_weekday_weekend_gold");
        assertThat(tool.getDescription()).contains("周末").contains("周中").contains("daily-trend");
        assertThat(tool.getParametersSchema()).containsKey("properties");
        assertThat(tool.getRequiredParameters()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // format() — classification + aggregation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-WWG-02: format() — weekend 日均高于周中 → 结论=周末日均更高")
    void format_weekendHigher_conclusionIsWeekend() {
        // Weekend: Sat 2026-03-07 (50,000) + Sun 2026-03-08 (50,000) + Sat 2026-03-14 (50,000)
        //   → total = 150,000 ; n=3 ; avg = 50,000
        // Weekday: Mon 2026-03-09 (20,000) + Tue 2026-03-10 (20,000) + Wed 2026-03-11 (20,000)
        //   → total = 60,000 ; n=3 ; avg = 20,000
        Map<String, Object> goldResult = buildTrendResult(List.of(
                point("2026-03-07", 50_000.0),  // Saturday
                point("2026-03-08", 50_000.0),  // Sunday
                point("2026-03-09", 20_000.0),  // Monday
                point("2026-03-10", 20_000.0),  // Tuesday
                point("2026-03-11", 20_000.0),  // Wednesday
                point("2026-03-14", 50_000.0)   // Saturday
        ));

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("结论", "周末日均更高");

        @SuppressWarnings("unchecked")
        Map<String, Object> weekend = (Map<String, Object>) result.get("周末");
        assertThat(weekend.get("天数")).isEqualTo(3);
        double avgWeekend = ((Number) weekend.get("日均营收")).doubleValue();
        assertThat(avgWeekend).isCloseTo(50_000.0, org.assertj.core.data.Offset.offset(1.0));

        @SuppressWarnings("unchecked")
        Map<String, Object> weekday = (Map<String, Object>) result.get("周中");
        assertThat(weekday.get("天数")).isEqualTo(3);
        double avgWeekday = ((Number) weekday.get("日均营收")).doubleValue();
        assertThat(avgWeekday).isCloseTo(20_000.0, org.assertj.core.data.Offset.offset(1.0));

        // Weekend avg (50,000) > weekday avg (20,000)
        assertThat(avgWeekend).isGreaterThan(avgWeekday);

        // message should be present and contain the conclusion
        assertThat(result).containsKey("message");
        assertThat(result.get("message").toString()).contains("结论");
    }

    @Test
    @DisplayName("UT-WWG-03: format() — 周中日均高于周末 → 结论=周中日均更高")
    void format_weekdayHigher_conclusionIsWeekday() {
        // Weekend: Sat 2026-03-07 (10,000) ; avg = 10,000
        // Weekday: Mon 2026-03-09 (100,000) ; avg = 100,000
        Map<String, Object> goldResult = buildTrendResult(List.of(
                point("2026-03-07", 10_000.0),   // Saturday
                point("2026-03-09", 100_000.0)   // Monday
        ));

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("结论", "周中日均更高");
    }

    @Test
    @DisplayName("UT-WWG-04: format() — 仅有周末数据, 周中天数=0, 结论=周末日均更高")
    void format_onlyWeekend_weekdayZero() {
        Map<String, Object> goldResult = buildTrendResult(List.of(
                point("2026-03-07", 30_000.0),   // Saturday
                point("2026-03-08", 20_000.0)    // Sunday
        ));

        Map<String, Object> result = tool.format(goldResult);

        @SuppressWarnings("unchecked")
        Map<String, Object> weekday = (Map<String, Object>) result.get("周中");
        assertThat(weekday.get("天数")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> weekend = (Map<String, Object>) result.get("周末");
        assertThat(((Number) weekend.get("日均营收")).doubleValue()).isGreaterThan(0);

        // avg weekend > avg weekday (0), so conclusion is weekend
        assertThat(result.get("结论")).isEqualTo("周末日均更高");
    }

    @Test
    @DisplayName("UT-WWG-05: format() — 均等营收, 结论=周末和周中基本持平")
    void format_equalAverage_conclusionIsEqual() {
        Map<String, Object> goldResult = buildTrendResult(List.of(
                point("2026-03-07", 10_000.0),   // Saturday
                point("2026-03-09", 10_000.0)    // Monday
        ));

        Map<String, Object> result = tool.format(goldResult);

        assertThat(result).containsEntry("结论", "周末和周中基本持平");
    }

    // -------------------------------------------------------------------------
    // isEmpty()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-WWG-06: isEmpty() — empty points → true")
    void isEmpty_emptyPoints() {
        assertThat(tool.isEmpty(buildTrendResult(List.of()))).isTrue();
    }

    @Test
    @DisplayName("UT-WWG-07: isEmpty() — missing points key → true")
    void isEmpty_missingKey() {
        assertThat(tool.isEmpty(new HashMap<>())).isTrue();
    }

    @Test
    @DisplayName("UT-WWG-08: isEmpty() — non-empty points → false")
    void isEmpty_nonEmpty() {
        assertThat(tool.isEmpty(buildTrendResult(List.of(point("2026-03-07", 5000.0))))).isFalse();
    }

    // -------------------------------------------------------------------------
    // doExecute() integration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UT-WWG-09: doExecute() — Gold returns trend → dataAvailable=true")
    void doExecute_goldReturnsTrend_dataAvailable() throws Exception {
        when(goldClient.fetchDataRange(FACTORY_ID))
                .thenReturn(Map.of(
                        "min_date", "2026-03-01",
                        "max_date", "2026-03-31",
                        "day_count", 31
                ));
        when(goldClient.fetchDailyTrend(any(), any(), any()))
                .thenReturn(buildTrendResult(List.of(
                        point("2026-03-07", 50_000.0),
                        point("2026-03-09", 30_000.0)
                )));

        Map<String, Object> result = tool.doExecute(FACTORY_ID, new HashMap<>(), new HashMap<>());

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsKey("周末");
        assertThat(result).containsKey("周中");
        assertThat(result).containsKey("结论");
        assertThat(result).containsKey("decisionBridge");
        assertThat(result).containsKey("suggestedFollowups");

        @SuppressWarnings("unchecked")
        Map<String, Object> bridge = (Map<String, Object>) result.get("decisionBridge");
        assertThat(bridge).containsEntry("answerMode", "report_with_owner_action");
        assertThat(bridge).containsEntry("ownerActionScenario", "package");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> followups = (List<Map<String, Object>>) result.get("suggestedFollowups");
        assertThat(followups).isNotEmpty();
        assertThat(followups.get(0)).containsEntry("ownerActionScenario", "package");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildTrendResult(List<Map<String, Object>> points) {
        Map<String, Object> m = new HashMap<>();
        m.put("factory_id", FACTORY_ID);
        m.put("start_date", "2026-03-01");
        m.put("end_date", "2026-03-31");
        m.put("points", new ArrayList<>(points));
        return m;
    }

    private static Map<String, Object> point(String date, double revenue) {
        Map<String, Object> p = new HashMap<>();
        p.put("date", date);
        p.put("revenue", revenue);
        p.put("bill_count", 50);
        p.put("avg_bill_value", revenue / 50.0);
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
