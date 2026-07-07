package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 (2026-07-07 design:
 * docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md)
 * delegate-gate tests for {@link GoldBackedRestaurantTool#doExecute}.
 *
 * <p>Exercised via {@link RestaurantPeakMonthGoldTool} — a concrete, no-arg-constructor
 * subclass — since the gate lives in the abstract base's {@code final doExecute}. Mirrors
 * the mock-injection pattern from {@link GoldBackedRestaurantToolTimeTest} (reflection-set
 * the protected {@code gold} field) and the direct-call pattern from
 * {@link RestaurantOpsGoldAnalysisToolTest} ({@code doExecute} is package-visible from this
 * same-package test).
 */
class GoldBackedRestaurantToolDelegateTest {

    private static final String FACTORY_ID = "RES_3101_009";

    private RestaurantPeakMonthGoldTool newTool(GoldFinanceClient goldClient) throws Exception {
        RestaurantPeakMonthGoldTool tool = new RestaurantPeakMonthGoldTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);
        return tool;
    }

    private static Map<String, Object> paramsWithInput(String userInput) {
        Map<String, Object> params = new HashMap<>();
        if (userInput != null) {
            params.put("userInput", userInput);
        }
        return params;
    }

    // =========================================================================
    // delegate:true → maps to a Tool result, original queryGold flow skipped
    // =========================================================================

    @Test
    @DisplayName("delegate:true answer → maps message/charts/kpis/code/contractPass, skips queryGold")
    void delegateTrueAnswerMapsToToolResult() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        List<Map<String, Object>> charts = List.of(Map.of("type", "line"));
        List<Map<String, Object>> kpis = List.of(Map.of("title", "毛利"));
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "answer_text", "最近2个月营收共120万，是赚钱的，毛利约1700。",
                        "charts", charts,
                        "kpis", kpis,
                        "code", "RESTAURANT_OPS_SALES_SUMMARY",
                        "contract_pass", true
                ));

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(
                FACTORY_ID, paramsWithInput("这两个月生意咋样，挣着钱没"), Collections.emptyMap());

        assertThat(result).containsEntry("dataAvailable", true);
        // ensureActionableMessage may append "\n\n建议：..." when the Python
        // answer_text doesn't already contain an advice-shaped word (per
        // containsActionAdvice) -- same behavior every other Gold Tool result
        // gets, so assert the delegated text is preserved as a PREFIX rather
        // than requiring byte-exact equality.
        assertThat((String) result.get("message")).startsWith("最近2个月营收共120万，是赚钱的，毛利约1700。");
        assertThat(result).containsEntry("tieredDelegate", true);
        assertThat(result).containsEntry("charts", charts);
        assertThat(result).containsEntry("kpis", kpis);
        assertThat(result).containsEntry("code", "RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(result).containsEntry("contractPass", true);
        // ensureActionableMessage ran (owner-action framing preserved).
        assertThat(result).containsKey("decisionBridge");
        assertThat(result).containsKey("suggestedFollowups");

        // The original resolveWindow -> queryGold flow (fetchDataRange /
        // fetchDailyTrend) must never run once the Python side delegated.
        verify(gold, never()).fetchDataRange(anyString());
        verify(gold, never()).fetchDailyTrend(anyString(), any(), any());
    }

    @Test
    @DisplayName("delegate:true clarification → message returned as-is, no owner-action framing bolted on")
    void delegateTrueClarificationReturnsMessageWithoutActionFraming() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "kind", "clarification",
                        "answer_text", "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
                ));

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(
                FACTORY_ID, paramsWithInput("情况怎么样"), Collections.emptyMap());

        assertThat(result).containsEntry("dataAvailable", true);
        assertThat(result).containsEntry("message",
                "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。");
        assertThat(result).containsEntry("tieredDelegate", true);
        // No owner-action framing on a clarifying question, and no
        // charts/kpis/code/contractPass keys the Python response didn't carry.
        assertThat(result).doesNotContainKey("decisionBridge");
        assertThat(result).doesNotContainKey("suggestedFollowups");
        assertThat(result).doesNotContainKey("actionAdvice");
        assertThat(result).doesNotContainKey("charts");
        assertThat(result).doesNotContainKey("kpis");
        assertThat(result).doesNotContainKey("code");
        assertThat(result).doesNotContainKey("contractPass");

        verify(gold, never()).fetchDataRange(anyString());
        verify(gold, never()).fetchDailyTrend(anyString(), any(), any());
    }

    // =========================================================================
    // delegate:false → original resolveWindow -> queryGold -> format flow runs
    // =========================================================================

    @Test
    @DisplayName("delegate:false falls through to the original queryGold/format flow, unchanged")
    void delegateFalseFallsThroughToOriginalFlow() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of("delegate", false));

        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(range);

        Map<String, Object> dailyTrend = new HashMap<>();
        dailyTrend.put("points", List.of(
                Map.of("date", "2026-03-01", "revenue", 10000.0),
                Map.of("date", "2026-04-01", "revenue", 30000.0)
        ));
        when(gold.fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(dailyTrend);

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(
                FACTORY_ID, paramsWithInput("营收趋势"), Collections.emptyMap());

        // The original flow's own output shape (RestaurantPeakMonthGoldTool.format),
        // not the delegate-gate mapping -- proves the gate fell through cleanly.
        assertThat(result).containsEntry("峰值月份", "2026-04");
        assertThat(result).doesNotContainKey("tieredDelegate");
        verify(gold).fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @DisplayName("fetchTieredIntentAnswer throwing → gate swallows it, falls through to original flow")
    void delegateGateExceptionFallsThroughToOriginalFlow() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenThrow(new RuntimeException("simulated unexpected client failure"));

        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(range);

        Map<String, Object> dailyTrend = new HashMap<>();
        dailyTrend.put("points", List.of(Map.of("date", "2026-04-01", "revenue", 5000.0)));
        when(gold.fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(dailyTrend);

        RestaurantPeakMonthGoldTool tool = newTool(gold);

        // Must not propagate — the delegate gate's try/catch must swallow it
        // (design section 4: "catch Exception 必须 log.warn 后 fall through，
        // 绝不向上抛").
        Map<String, Object> result = tool.doExecute(
                FACTORY_ID, paramsWithInput("营收趋势"), Collections.emptyMap());

        assertThat(result).containsEntry("峰值月份", "2026-04");
        assertThat(result).doesNotContainKey("tieredDelegate");
        verify(gold).fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class));
    }

    // =========================================================================
    // userInput missing/blank → gate skipped entirely, never calls the client
    // =========================================================================

    @Test
    @DisplayName("missing userInput → delegate gate skipped, fetchTieredIntentAnswer never called")
    void missingUserInputSkipsDelegateGate() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);

        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(range);

        Map<String, Object> dailyTrend = new HashMap<>();
        dailyTrend.put("points", List.of(Map.of("date", "2026-04-01", "revenue", 5000.0)));
        when(gold.fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(dailyTrend);

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(FACTORY_ID, paramsWithInput(null), Collections.emptyMap());

        assertThat(result).containsEntry("峰值月份", "2026-04");
        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("blank userInput → delegate gate skipped, fetchTieredIntentAnswer never called")
    void blankUserInputSkipsDelegateGate() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);

        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(range);

        Map<String, Object> dailyTrend = new HashMap<>();
        dailyTrend.put("points", List.of(Map.of("date", "2026-04-01", "revenue", 5000.0)));
        when(gold.fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(dailyTrend);

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(FACTORY_ID, paramsWithInput("   "), Collections.emptyMap());

        assertThat(result).containsEntry("峰值月份", "2026-04");
        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString());
    }
}
