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
                .thenReturn(Map.of(
                        "delegate", true,
                        "answer_text", "「米饭(单人份)」销量 100 份。",
                        "charts", List.of(),
                        "kpis", List.of(),
                        "code", "RESTAURANT_OPS_GROSS_MARGIN",
                        "contract_pass", true));

        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "米饭的销量是多少");
        Map<String, Object> result = newDelegate(gold).tryDelegate(
                FACTORY_ID, params, Map.of(), "restaurant_dish_sales_ranking");

        assertThat(result).isNotNull()
                .containsEntry("tieredDelegate", true)
                .containsEntry("code", "RESTAURANT_OPS_GROSS_MARGIN");
        assertThat((String) result.get("message")).startsWith("「米饭(单人份)」");
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
