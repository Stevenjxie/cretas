package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.client.GoldFinanceClient;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sheet 7/22 菜品链: legacy restaurant tools' shared delegate gate. */
class TieredIntentDelegateTest {

    private static final String FACTORY_ID = "DEMO_REST";

    private TieredIntentDelegate newDelegate(GoldFinanceClient gold) throws Exception {
        TieredIntentDelegate delegate = new TieredIntentDelegate();
        Field f = TieredIntentDelegate.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(delegate, gold);
        return delegate;
    }

    @Test
    @DisplayName("delegate:true → tool-result map with message/charts/code")
    void delegateTrueMapsResult() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), eq("米饭的销量是多少"), eq("restaurant_dish_sales_ranking")))
                .thenReturn(Map.ofEntries(
                        Map.entry("delegate", true),
                        Map.entry("answer_text", "「米饭(单人份)」销量 100 份。"),
                        Map.entry("charts", List.of()),
                        Map.entry("kpis", List.of()),
                        Map.entry("code", "RESTAURANT_OPS_GROSS_MARGIN"),
                        Map.entry("warning", "咨询模式只展示分析结果，没有执行下架操作。"),
                        Map.entry("contract_pass", true),
                        Map.entry("query_plan_hash", "plan-42"),
                        Map.entry(
                                "executed_resolvers",
                                List.of("RESTAURANT_OPS_GROSS_MARGIN")),
                        Map.entry("suggested_followups", List.of(Map.of(
                                "label", "看菜品成本",
                                "question", "米饭(单人份)的成本如何？"))),
                        Map.entry("structured_context", Map.of(
                                "window_label", "本月",
                                "requested_metrics", List.of("sales_volume")))));

        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "米饭的销量是多少");
        Map<String, Object> result = newDelegate(gold).tryDelegate(
                FACTORY_ID, params, Map.of(), "restaurant_dish_sales_ranking");

        assertThat(result).isNotNull()
                .containsEntry("tieredDelegate", true)
                .containsEntry("code", "RESTAURANT_OPS_GROSS_MARGIN")
                .containsEntry("warning", "咨询模式只展示分析结果，没有执行下架操作。")
                .containsEntry("queryPlanHash", "plan-42");
        assertThat(result.get("executedResolvers"))
                .isEqualTo(List.of("RESTAURANT_OPS_GROSS_MARGIN"));
        assertThat(result.get("suggestedFollowups")).isEqualTo(List.of(Map.of(
                "label", "看菜品成本",
                "question", "米饭(单人份)的成本如何？")));
        assertThat(result.get("conversationContext")).isEqualTo(Map.of(
                "window_label", "本月",
                "requested_metrics", List.of("sales_volume")));
        assertThat((String) result.get("message")).startsWith("「米饭(单人份)」");
    }

    @Test
    @DisplayName("clarification keeps time buttons and conversation context")
    void clarificationMapsTimeButtonsAndContext() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        List<Map<String, Object>> followups = List.of(
                Map.of("label", "本月", "question", "本月"),
                Map.of("label", "上个月", "question", "上个月"));
        Map<String, Object> structuredContext = Map.of(
                "focus_entity", Map.of("type", "dish", "name", "招牌菜"),
                "requested_metrics", List.of("sales_volume"));
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), eq("招牌菜销量"), eq("restaurant_dish_sales_ranking")))
                .thenReturn(Map.of(
                        "delegate", true,
                        "kind", "clarification",
                        "answer_text", "你想看哪个时间范围？",
                        "suggested_followups", followups,
                        "structured_context", structuredContext));

        Map<String, Object> result = newDelegate(gold).tryDelegate(
                FACTORY_ID,
                new HashMap<>(Map.of("userInput", "招牌菜销量")),
                Map.of(),
                "restaurant_dish_sales_ranking");

        assertThat(result).containsEntry("message", "你想看哪个时间范围？");
        assertThat(result).containsEntry("suggestedFollowups", followups);
        assertThat(result).containsEntry("conversationContext", structuredContext);
    }

    @Test
    @DisplayName("userInput recovered from context request; sessionId forwarded")
    void contextRecoveryAndSessionForwarding() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(eq(FACTORY_ID), eq("成本如何"), anyString(), eq("sess-9")))
                .thenReturn(Map.of("delegate", true, "answer_text", "米饭成本 ¥1.20。"));

        IntentExecuteRequest req = new IntentExecuteRequest();
        req.setUserInput("成本如何");
        req.setSessionId("sess-9");
        Map<String, Object> context = new HashMap<>();
        context.put("request", req);

        Map<String, Object> result = newDelegate(gold).tryDelegate(
                FACTORY_ID, new HashMap<>(), context, "restaurant_dish_cost_query");

        assertThat(result).isNotNull().containsEntry("tieredDelegate", true);
        verify(gold).fetchTieredIntentAnswer(FACTORY_ID, "成本如何", "restaurant_dish_cost_query", "sess-9");
    }

    @Test
    @DisplayName("delegate:false / null / blank input → null (caller falls through)")
    void fallThroughCases() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("delegate", false));

        TieredIntentDelegate delegate = newDelegate(gold);
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "随便问问");
        assertThat(delegate.tryDelegate(FACTORY_ID, params, Map.of(), "t")).isNull();
        assertThat(delegate.tryDelegate(FACTORY_ID, new HashMap<>(), Map.of(), "t")).isNull();
        assertThat(delegate.tryDelegate(FACTORY_ID, null, null, "t")).isNull();
    }

    @Test
    @DisplayName("R16: attempted flag in context or request-context dedups to null")
    void attemptedFlagDedup() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        TieredIntentDelegate delegate = newDelegate(gold);
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "米饭的销量是多少");

        Map<String, Object> flaggedContext = new HashMap<>();
        flaggedContext.put(TieredIntentDelegate.ATTEMPTED_CONTEXT_KEY, Boolean.TRUE);
        assertThat(delegate.tryDelegate(FACTORY_ID, params, flaggedContext, "t")).isNull();

        IntentExecuteRequest req = new IntentExecuteRequest();
        req.setUserInput("米饭的销量是多少");
        Map<String, Object> reqCtx = new HashMap<>();
        reqCtx.put(TieredIntentDelegate.ATTEMPTED_CONTEXT_KEY, Boolean.TRUE);
        req.setContext(reqCtx);
        Map<String, Object> outerContext = new HashMap<>();
        outerContext.put("request", req);
        assertThat(delegate.tryDelegate(FACTORY_ID, params, outerContext, "t")).isNull();
        org.mockito.Mockito.verifyNoInteractions(gold);
    }

    @Test
    @DisplayName("gold client throwing → null, never propagates")
    void exceptionIsSwallowed() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        when(gold.fetchTieredIntentAnswer(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "米饭的销量是多少");
        assertThat(newDelegate(gold).tryDelegate(FACTORY_ID, params, Map.of(), "t")).isNull();
    }
}
