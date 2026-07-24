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
        List<Map<String, Object>> followups = List.of(Map.of(
                "label", "看菜品成本",
                "question", "招牌菜的成本如何？"));
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "answer_text", "最近2个月营收共120万，是赚钱的，毛利约1700。",
                        "charts", charts,
                        "kpis", kpis,
                        "code", "RESTAURANT_OPS_SALES_SUMMARY",
                        "contract_pass", true,
                        "query_plan_hash", "plan-42",
                        "suggested_followups", followups,
                        "executed_resolvers", List.of("RESTAURANT_OPS_GROSS_MARGIN")
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
        assertThat(result).containsEntry("queryPlanHash", "plan-42");
        assertThat(result).containsEntry(
                "executedResolvers", List.of("RESTAURANT_OPS_GROSS_MARGIN"));
        assertThat(result).containsEntry("suggestedFollowups", followups);
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
        List<Map<String, Object>> timeFollowups = List.of(
                Map.of("label", "本月", "question", "本月"),
                Map.of("label", "上个月", "question", "上个月"));
        Map<String, Object> structuredContext = Map.of(
                "requested_metrics", List.of("revenue"));
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "kind", "clarification",
                        "answer_text", "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。",
                        "suggested_followups", timeFollowups,
                        "structured_context", structuredContext
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
        assertThat(result).containsEntry("suggestedFollowups", timeFollowups);
        assertThat(result).containsEntry("conversationContext", structuredContext);
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

    @Test
    @DisplayName("DEMO_REST → delegate gate receives the RAW factoryId, not the RES_3101_009 gold alias")
    void demoRestUsesRawFactoryIdForDelegateGate() throws Exception {
        // 2026-07-08 audit fix C-3: the single most deliberate decision in the
        // Phase 2 gate is passing the RAW factoryId to Python (DEMO_REST has
        // its own seeded restaurant-ops Gold data, V20260706_01; the
        // resolveGoldFactoryId DEMO_REST→RES_3101_009 alias is for the OTHER
        // GoldFinanceClient fetches only). This pins that behavior so a future
        // refactor that moves the gate below resolveGoldFactoryId() fails here
        // instead of silently breaking the public demo tenant.
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq("DEMO_REST"), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "answer_text", "最近2个月是赚钱的，毛利约 ¥100。建议：保持。"
                ));

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(
                "DEMO_REST", paramsWithInput("这两个月挣着钱没"), Collections.emptyMap());

        assertThat(result).containsEntry("tieredDelegate", true);
        verify(gold).fetchTieredIntentAnswer(eq("DEMO_REST"), anyString(), eq("restaurant_peak_month_gold"));
        verify(gold, never()).fetchTieredIntentAnswer(eq("RES_3101_009"), anyString(), anyString());
        // The delegated path never reaches the Gold data fetches, so the alias
        // is irrelevant here -- but assert no accidental aliased call happened.
        verify(gold, never()).fetchDataRange(anyString());
    }

    // =========================================================================
    // 2026-07-08 clarification-loop v1: session id extraction from context
    // (best-effort -- only ToolDispatchService.executeWithTool stashes the
    // raw IntentExecuteRequest under context["request"]).
    // =========================================================================

    @Test
    @DisplayName("context carries IntentExecuteRequest with sessionId -> 4-arg fetchTieredIntentAnswer overload used")
    void contextWithSessionIdUsesFourArgOverload() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(
                eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold"), eq("sess-xyz")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "answer_text", "最近2个月营收共120万，是赚钱的。"
                ));

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> context = new HashMap<>();
        context.put("request", com.cretas.aims.dto.ai.IntentExecuteRequest.builder()
                .sessionId("sess-xyz").build());

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID, paramsWithInput("这两个月生意咋样，挣着钱没"), context);

        assertThat(result).containsEntry("tieredDelegate", true);
        verify(gold).fetchTieredIntentAnswer(
                eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold"), eq("sess-xyz"));
        // The 3-arg overload must NOT also be invoked for this call.
        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("context carries IntentExecuteRequest with null sessionId -> falls back to 3-arg overload")
    void contextWithNullSessionIdFallsBackToThreeArgOverload() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of("delegate", false));

        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(range);
        Map<String, Object> dailyTrend = new HashMap<>();
        dailyTrend.put("points", List.of(Map.of("date", "2026-04-01", "revenue", 5000.0)));
        when(gold.fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(dailyTrend);

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> context = new HashMap<>();
        context.put("request", com.cretas.aims.dto.ai.IntentExecuteRequest.builder()
                .sessionId(null).build());

        tool.doExecute(FACTORY_ID, paramsWithInput("营收趋势"), context);

        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString(), anyString());
        verify(gold).fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold"));
    }

    @Test
    @DisplayName("context without a \"request\" key (e.g. DynamicToolSelectionService dispatch) -> 3-arg overload, unchanged")
    void contextWithoutRequestKeyFallsBackToThreeArgOverload() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of("delegate", false));

        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(range);
        Map<String, Object> dailyTrend = new HashMap<>();
        dailyTrend.put("points", List.of(Map.of("date", "2026-04-01", "revenue", 5000.0)));
        when(gold.fetchDailyTrend(eq(FACTORY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(dailyTrend);

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        // A context with OTHER keys but no "request" -- mirrors
        // DynamicToolSelectionService / LlmIntentFallbackClientImpl's leaner
        // context shape.
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", FACTORY_ID);
        context.put("userId", 1L);

        tool.doExecute(FACTORY_ID, paramsWithInput("营收趋势"), context);

        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString(), anyString());
        verify(gold).fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold"));
    }

    @Test
    @DisplayName("userInput missing from params but present in context request → gate recovers it and delegates")
    void contextRequestRecoversUserInputForDelegateGate() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), anyString(), eq("restaurant_peak_month_gold")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "answer_text", "最近30天营收共36万。",
                        "charts", List.of(),
                        "kpis", List.of(),
                        "code", "RESTAURANT_OPS_SALES_SUMMARY",
                        "contract_pass", true
                ));

        com.cretas.aims.dto.ai.IntentExecuteRequest req = new com.cretas.aims.dto.ai.IntentExecuteRequest();
        req.setUserInput("过去一个月营业额");
        Map<String, Object> context = new HashMap<>();
        context.put("request", req);

        RestaurantPeakMonthGoldTool tool = newTool(gold);
        Map<String, Object> result = tool.doExecute(FACTORY_ID, paramsWithInput(null), context);

        assertThat(result).containsEntry("tieredDelegate", true);
        assertThat((String) result.get("message")).startsWith("最近30天营收共36万。");
        verify(gold).fetchTieredIntentAnswer(eq(FACTORY_ID), eq("过去一个月营业额"), eq("restaurant_peak_month_gold"));
        verify(gold, never()).fetchDataRange(anyString());
    }
}
