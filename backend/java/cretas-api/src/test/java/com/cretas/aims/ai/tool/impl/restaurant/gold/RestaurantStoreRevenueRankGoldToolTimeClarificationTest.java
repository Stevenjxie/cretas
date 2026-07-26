package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.client.GoldFinanceClient;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Restaurant store ranking explicit-time gate")
class RestaurantStoreRevenueRankGoldToolTimeClarificationTest {

    private static final String FACTORY_ID = "RES_3101_009";

    @Mock
    private GoldFinanceClient gold;

    private RestaurantStoreRevenueRankGoldTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantStoreRevenueRankGoldTool();
        Field field = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        field.setAccessible(true);
        field.set(tool, gold);
    }

    @Test
    @DisplayName("missing time asks four deterministic choices without Gold or LLM")
    void missingTimeClarifiesWithoutQueryingGoldOrTieredRouter() throws Exception {
        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                Map.of("userInput", "哪家店业绩最好"),
                Collections.emptyMap());

        assertThat(result)
                .containsEntry("dataAvailable", true)
                .containsEntry("clarificationRequired", true)
                .containsEntry(
                        "message",
                        "你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> followups =
                (List<Map<String, Object>>) result.get("suggestedFollowups");
        assertThat(followups).containsExactly(
                Map.of("label", "本月", "question", "本月哪家店业绩最好"),
                Map.of("label", "上个月", "question", "上个月哪家店业绩最好"),
                Map.of("label", "最近7天", "question", "最近7天哪家店业绩最好"),
                Map.of("label", "最近30天", "question", "最近30天哪家店业绩最好"));

        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString());
        verify(gold, never()).fetchDataRange(anyString());
        verify(gold, never()).fetchFinanceSummary(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Integer.class));
    }

    @Test
    @DisplayName("missing-time store-ranking synonyms use the same deterministic contract")
    void missingTimeSynonymsUseTheSameContract() throws Exception {
        for (String query : List.of(
                "哪个店业绩最好？",
                "哪家门店营收最高？",
                "最赚钱的店是哪家？",
                "门店营业额最高的是谁？")) {
            Map<String, Object> result = tool.doExecute(
                    FACTORY_ID,
                    Map.of("userInput", query),
                    Collections.emptyMap());

            assertThat(result)
                    .as(query)
                    .containsEntry("clarificationRequired", true)
                    .containsEntry(
                            "message",
                            "你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。");
        }

        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString());
        verify(gold, never()).fetchDataRange(anyString());
        verify(gold, never()).fetchFinanceSummary(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Integer.class));
    }

    @Test
    @DisplayName("raw query recovered from context is still subject to the time gate")
    void contextRecoveredQueryCannotBypassTimeGate() throws Exception {
        IntentExecuteRequest request = new IntentExecuteRequest();
        request.setUserInput("哪家门店业绩最好？");

        Map<String, Object> context = new HashMap<>();
        context.put("request", request);

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                Collections.emptyMap(),
                context);

        assertThat(result).containsEntry("clarificationRequired", true);
        verify(gold, never()).fetchDataRange(anyString());
        verify(gold, never()).fetchFinanceSummary(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Integer.class));
    }

    @Test
    @DisplayName("explicit time recovered from context drives the Gold window instead of all history")
    void contextRecoveredExplicitTimeDrivesGoldWindow() throws Exception {
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(Map.of(
                "min_date", "2025-01-01",
                "max_date", "2026-04-30"));
        when(gold.fetchFinanceSummary(
                eq(FACTORY_ID),
                eq(LocalDate.of(2026, 4, 1)),
                eq(LocalDate.of(2026, 4, 30)),
                eq(50)))
                .thenReturn(goldResult("2026-04-01", "2026-04-30"));

        IntentExecuteRequest request = new IntentExecuteRequest();
        request.setUserInput("最近30天哪家店业绩最好");
        Map<String, Object> context = new HashMap<>();
        context.put("request", request);

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                Collections.emptyMap(),
                context);

        assertThat(result).doesNotContainKey("clarificationRequired");
        assertThat(result.get("message").toString())
                .contains("第一名是人民广场店")
                .contains("2026-04-01 至 2026-04-30");
        verify(gold).fetchFinanceSummary(
                FACTORY_ID,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                50);
    }

    @Test
    @DisplayName("an invalid extracted month cannot authorize an all-history ranking")
    void invalidMonthStillRequiresClarification() throws Exception {
        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                Map.of(
                        "userInput", "哪家店业绩最好",
                        "month", "全部数据"),
                Collections.emptyMap());

        assertThat(result).containsEntry("clarificationRequired", true);
        verify(gold, never()).fetchDataRange(anyString());
        verify(gold, never()).fetchFinanceSummary(
                anyString(), any(LocalDate.class), any(LocalDate.class), any(Integer.class));
    }

    @Test
    @DisplayName("selected recent-7-days button preserves ranking semantics and executes that window")
    void selectedTimeButtonExecutesExplicitWindow() throws Exception {
        when(gold.fetchDataRange(FACTORY_ID)).thenReturn(Map.of(
                "min_date", "2025-01-01",
                "max_date", "2026-04-30"));
        when(gold.fetchFinanceSummary(
                eq(FACTORY_ID),
                eq(LocalDate.of(2026, 4, 24)),
                eq(LocalDate.of(2026, 4, 30)),
                eq(50)))
                .thenReturn(goldResult("2026-04-24", "2026-04-30"));

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                Map.of("userInput", "最近7天哪家店业绩最好"),
                Collections.emptyMap());

        assertThat(result).doesNotContainKey("clarificationRequired");
        assertThat(result.get("message").toString())
                .contains("第一名是人民广场店")
                .contains("2026-04-24 至 2026-04-30");
        verify(gold, never()).fetchTieredIntentAnswer(anyString(), anyString(), anyString());
        verify(gold).fetchFinanceSummary(
                FACTORY_ID,
                LocalDate.of(2026, 4, 24),
                LocalDate.of(2026, 4, 30),
                50);
    }

    @Test
    @DisplayName("explicit ISO dates execute even when the raw question has no time phrase")
    void normalizedIsoWindowCountsAsExplicitTime() throws Exception {
        when(gold.fetchFinanceSummary(
                eq(FACTORY_ID),
                eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)),
                eq(50)))
                .thenReturn(goldResult("2026-03-01", "2026-03-31"));

        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "哪家店业绩最好");
        params.put("startDate", "2026-03-01");
        params.put("endDate", "2026-03-31");

        Map<String, Object> result = tool.doExecute(
                FACTORY_ID,
                params,
                Collections.emptyMap());

        assertThat(result).doesNotContainKey("clarificationRequired");
        assertThat(result.get("message").toString()).contains("2026-03-01 至 2026-03-31");
        verify(gold).fetchFinanceSummary(
                FACTORY_ID,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                50);
    }

    private static Map<String, Object> goldResult(String start, String end) {
        return Map.of(
                "start_date", start,
                "end_date", end,
                "total_revenue", 3000.0,
                "store_count", 2,
                "top_stores", List.of(
                        Map.of(
                                "store_id", 101,
                                "store_name", "人民广场店",
                                "revenue", 2000.0,
                                "bill_count", 20),
                        Map.of(
                                "store_id", 102,
                                "store_name", "陆家嘴店",
                                "revenue", 1000.0,
                                "bill_count", 25)));
    }
}
