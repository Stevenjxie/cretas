package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.dto.python.PythonRestaurantSectionRequest;
import com.cretas.aims.dto.python.PythonRestaurantSectionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 11.5 Phase 1 — verify {@link RestaurantStorePnlOnePagerTool} wires
 * {@code financial_metrics} into Python {@code request.params} so the
 * {@code store_pnl_one_pager} section no longer skips with
 * "未提供 financial_metrics".
 */
@ExtendWith(MockitoExtension.class)
class RestaurantStorePnlOnePagerToolTest {

    private static final String FACTORY = "RES_3101_009";

    @Mock
    private PythonSmartBIClient pythonClient;

    @Mock
    private RestaurantFinancialMetricsFetcher metricsFetcher;

    private RestaurantStorePnlOnePagerTool tool;

    @BeforeEach
    void setUp() {
        tool = new RestaurantStorePnlOnePagerTool();
        tool.pythonClient = pythonClient;
        tool.metricsFetcher = metricsFetcher;
    }

    private PythonRestaurantSectionResponse okResponse(Map<String, Object> data) {
        PythonRestaurantSectionResponse resp = new PythonRestaurantSectionResponse();
        resp.setSuccess(true);
        resp.setStatus("ok");
        resp.setSectionName("store_pnl_one_pager");
        resp.setData(data);
        resp.setWarnings(List.of());
        return resp;
    }

    @Test
    @DisplayName("metadata — toolName / sectionName / description / month-aware schema")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_store_pnl_one_pager");
        assertThat(tool.getSectionName()).isEqualTo("store_pnl_one_pager");
        assertThat(tool.getDescription()).contains("单店 P&L 一页纸");

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.getParametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("month");
        assertThat(props).containsKey("sub_sector");
        assertThat(props).containsKey("store_id");
    }

    @Test
    @DisplayName("happy path — fetcher returns metrics, gets injected into Python params")
    void happyPath_injectsFinancialMetricsIntoPythonParams() throws Exception {
        Map<String, Object> fakeMetrics = new LinkedHashMap<>();
        fakeMetrics.put("revenue", 731048.0);
        fakeMetrics.put("foodCost", 335213.0);
        fakeMetrics.put("laborCost", 237660.0);
        fakeMetrics.put("restaurantNetMargin", -6.8);

        when(metricsFetcher.fetch(eq(FACTORY), any())).thenReturn(Optional.of(fakeMetrics));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of(
                        "headline", "2026-04 净亏 ¥49,724 (-6.80%)",
                        "headlineColor", "red"))));

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "火锅", "month", "上月"),
                new HashMap<>());

        assertThat(result).containsEntry("success", true);
        assertThat(((Map<?, ?>) result.get("data")).get("headline"))
                .asString().contains("2026-04 净亏");

        // Verify the request to Python actually contained financial_metrics
        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_pnl_one_pager"), reqCaptor.capture());
        PythonRestaurantSectionRequest sent = reqCaptor.getValue();
        assertThat(sent.getParams()).containsKey("financial_metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> sentMetrics = (Map<String, Object>) sent.getParams().get("financial_metrics");
        assertThat(sentMetrics.get("revenue")).isEqualTo(731048.0);
        assertThat(sentMetrics.get("foodCost")).isEqualTo(335213.0);

        // Verify framework keys are stripped (sub_sector lives on the
        // request envelope, not in params)
        assertThat(sent.getParams()).doesNotContainKey("sub_sector");
        assertThat(sent.getParams()).doesNotContainKey("store_id");
        assertThat(sent.getParams()).doesNotContainKey("upload_id");
        assertThat(sent.getParams()).doesNotContainKey("store_name");
        assertThat(sent.getSubSector()).isEqualTo("火锅");
    }

    @Test
    @DisplayName("no data — fetcher returns empty, no financial_metrics sent, Python skips")
    void noData_doesNotInjectFinancialMetrics_pythonSkips() throws Exception {
        when(metricsFetcher.fetch(eq(FACTORY), any())).thenReturn(Optional.empty());

        PythonRestaurantSectionResponse skipResp = new PythonRestaurantSectionResponse();
        skipResp.setSuccess(false);
        skipResp.setStatus("skipped");
        skipResp.setSectionName("store_pnl_one_pager");
        skipResp.setWarnings(List.of("未提供 financial_metrics (需 DiagnosticsHandler 先运行或直接传入)"));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(skipResp));

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "火锅", "month", "上月"),
                new HashMap<>());

        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("message")).contains("financial_metrics");

        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_pnl_one_pager"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getParams()).doesNotContainKey("financial_metrics");
    }

    @Test
    @DisplayName("caller override — params['financial_metrics'] preempts auto-fetch")
    void callerOverride_skipsFetcher() throws Exception {
        Map<String, Object> callerMetrics = Map.of(
                "revenue", 500000.0,
                "foodCost", 200000.0);

        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of("headline", "test"))));

        Map<String, Object> params = new HashMap<>();
        params.put("sub_sector", "火锅");
        params.put("financial_metrics", callerMetrics);

        tool.doExecute(FACTORY, params, new HashMap<>());

        // Fetcher should NOT be called because caller supplied financial_metrics
        verifyNoInteractions(metricsFetcher);

        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_pnl_one_pager"), reqCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) reqCaptor.getValue()
                .getParams().get("financial_metrics");
        assertThat(sent.get("revenue")).isEqualTo(500000.0);
    }

    @Test
    @DisplayName("context override — context['financial_metrics'] preempts auto-fetch (future composite handoff)")
    void contextOverride_skipsFetcher() throws Exception {
        Map<String, Object> ctxMetrics = Map.of(
                "revenue", 999.0,
                "foodCost", 100.0);

        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of("headline", "ctx"))));

        Map<String, Object> context = new HashMap<>();
        context.put("financial_metrics", ctxMetrics);

        tool.doExecute(FACTORY, Map.of("sub_sector", "火锅"), context);

        verifyNoInteractions(metricsFetcher);

        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_pnl_one_pager"), reqCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) reqCaptor.getValue()
                .getParams().get("financial_metrics");
        assertThat(sent.get("revenue")).isEqualTo(999.0);
    }

    @Test
    @DisplayName("month param threaded to fetcher")
    void monthParam_threadedToFetcher() throws Exception {
        when(metricsFetcher.fetch(eq(FACTORY), eq("2026-04")))
                .thenReturn(Optional.of(Map.of("revenue", 100.0)));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of("headline", "April"))));

        tool.doExecute(FACTORY,
                Map.of("sub_sector", "火锅", "month", "2026-04"),
                new HashMap<>());

        verify(metricsFetcher, times(1)).fetch(FACTORY, "2026-04");
    }

    @Test
    @DisplayName("month param missing — passes null to fetcher (fetcher defaults '上月')")
    void monthParamMissing_passesNullToFetcher() throws Exception {
        when(metricsFetcher.fetch(eq(FACTORY), eq(null)))
                .thenReturn(Optional.of(Map.of("revenue", 100.0)));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of("headline", "default"))));

        tool.doExecute(FACTORY, Map.of("sub_sector", "火锅"), new HashMap<>());

        verify(metricsFetcher, times(1)).fetch(FACTORY, null);
    }

    @Test
    @DisplayName("Python unavailable — buildError path triggered, fetcher still invoked")
    void pythonUnavailable_returnsBuildError() throws Exception {
        when(metricsFetcher.fetch(eq(FACTORY), any()))
                .thenReturn(Optional.of(Map.of("revenue", 100.0)));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "火锅"), new HashMap<>());

        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("message")).containsIgnoringCase("python");
    }

    @Test
    @DisplayName("non-Map caller financial_metrics — falls through to auto-fetch (defensive)")
    void invalidCallerFinancialMetrics_fallsThroughToFetch() throws Exception {
        when(metricsFetcher.fetch(eq(FACTORY), any()))
                .thenReturn(Optional.of(Map.of("revenue", 222.0)));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of("headline", "auto"))));

        Map<String, Object> params = new HashMap<>();
        params.put("sub_sector", "火锅");
        params.put("financial_metrics", "not-a-map");  // bad shape

        tool.doExecute(FACTORY, params, new HashMap<>());

        verify(metricsFetcher, times(1)).fetch(FACTORY, null);

        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_pnl_one_pager"), reqCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) reqCaptor.getValue()
                .getParams().get("financial_metrics");
        assertThat(sent.get("revenue")).isEqualTo(222.0);
    }

    @Test
    @DisplayName("non-Map context['financial_metrics'] — falls through to auto-fetch (defensive)")
    void invalidContextFinancialMetrics_fallsThroughToFetch() throws Exception {
        when(metricsFetcher.fetch(eq(FACTORY), any()))
                .thenReturn(Optional.of(Map.of("revenue", 333.0)));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(okResponse(Map.of("headline", "auto"))));

        Map<String, Object> context = new HashMap<>();
        context.put("financial_metrics", List.of("not a map"));

        tool.doExecute(FACTORY, Map.of("sub_sector", "火锅"), context);

        verify(metricsFetcher, times(1)).fetch(FACTORY, null);
    }

    @Test
    @DisplayName("Python skip with caller-provided metrics — fetcher still untouched")
    void pythonSkipsButCallerProvided_noFetch() throws Exception {
        PythonRestaurantSectionResponse skipResp = new PythonRestaurantSectionResponse();
        skipResp.setSuccess(false);
        skipResp.setStatus("skipped");
        skipResp.setSectionName("store_pnl_one_pager");
        skipResp.setWarnings(List.of("downstream error"));
        when(pythonClient.callRestaurantSection(eq("store_pnl_one_pager"), any()))
                .thenReturn(Optional.of(skipResp));

        Map<String, Object> params = new HashMap<>();
        params.put("financial_metrics", Map.of("revenue", 1.0));

        Map<String, Object> result = tool.doExecute(FACTORY, params, new HashMap<>());

        assertThat(result).containsEntry("success", false);
        verify(metricsFetcher, never()).fetch(any(), any());
    }
}
