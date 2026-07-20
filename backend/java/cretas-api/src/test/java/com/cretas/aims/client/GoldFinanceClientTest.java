package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GoldFinanceClient — HTTP + arg validation.
 *
 * Uses MockWebServer (same pattern as PythonSmartBIClientTest) so tests
 * exercise the actual OkHttp request path including URL construction,
 * headers, and body parsing — not just argument validation.
 */
class GoldFinanceClientTest {

    private MockWebServer server;
    private GoldFinanceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        config.setUrl(server.url("").toString().replaceAll("/$", ""));
        config.setTimeout(5000);
        config.setConnectTimeout(5000);

        PythonSmartBIClient typedClient = new PythonSmartBIClient(
                config,
                new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build(),
                new ObjectMapper(),
                new PythonServiceCircuitBreaker(),
                "typed-secret-xyz");
        client = new GoldFinanceClient(config, typedClient);
        // Simulate the @Value injection.
        ReflectionTestUtils.setField(client, "internalSecret", "test-secret-abc");
    }

    @AfterEach
    void tearDown() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        server.shutdown();
    }

    @Test
    void fetchRestaurantOpsAnalysisUsesTypedTransportAndTrustedHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"answer\":\"ops answer\",\"charts\":[]}"));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute("userId", 88L);
        servletRequest.setAttribute("role", "restaurant_manager");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        Map<String, Object> result = client.fetchRestaurantOpsAnalysis(
                "REST-OPS", "show restaurant costs", "session-ops",
                "RESTAURANT_OPS_GROSS_MARGIN");

        assertEquals("ops answer", result.get("answer"));
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/chat/general-analysis", request.getPath());
        assertEquals("typed-secret-xyz", request.getHeader("X-Internal-Secret"));
        assertEquals("REST-OPS", request.getHeader("X-Factory-Id"));
        assertEquals("88", request.getHeader("X-User-Id"));
        assertEquals("restaurant_manager", request.getHeader("X-User-Role"));
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertEquals("show restaurant costs", body.path("query").asText());
        assertEquals("restaurant_ops", body.path("table_type").asText());
        assertEquals("session-ops", body.path("session_id").asText());
        assertEquals("RESTAURANT_OPS_GROSS_MARGIN", body.path("expected_intent").asText());
        assertFalse(body.path("allow_tenant_data_fallback").asBoolean(true));
        assertFalse(body.has("factory_id"));
        assertFalse(body.has("user_id"));
    }

    @Test
    void fetchRestaurantOpsAnalysisRejectsEmptySuccessfulResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"answer\":\"  \"}"));

        IOException failure = assertThrows(IOException.class, () ->
                client.fetchRestaurantOpsAnalysis("REST-OPS", "question", null));

        assertEquals("Python SmartBI general analysis is unavailable", failure.getMessage());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void fetchFinanceSummary_happyPath_parsesResponse() throws Exception {
        String body = "{"
                + "\"factory_id\":\"F001\","
                + "\"start_date\":\"2025-01-01\","
                + "\"end_date\":\"2025-12-31\","
                + "\"total_revenue\":20639884.52,"
                + "\"bill_count\":140541,"
                + "\"avg_bill_value\":146.86,"
                + "\"store_count\":8,"
                + "\"day_count\":365,"
                + "\"top_stores\":[]"
                + "}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        Map<String, Object> result = client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31),
                5);

        assertEquals("F001", result.get("factory_id"));
        assertEquals(140541, ((Number) result.get("bill_count")).intValue());
        assertEquals(8, ((Number) result.get("store_count")).intValue());
    }

    @Test
    void fetchFinanceSummary_sendsRequiredQueryParamsAndHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 4, 30),
                10);

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        String path = req.getPath();
        assertTrue(path.startsWith("/api/smartbi/gold/finance-summary"), path);
        assertTrue(path.contains("factory_id=F001"), path);
        assertTrue(path.contains("start_date=2025-04-01"), path);
        assertTrue(path.contains("end_date=2025-04-30"), path);
        assertTrue(path.contains("top_n_stores=10"), path);
        assertEquals("test-secret-abc", req.getHeader("X-Internal-Secret"));
        assertEquals("F001", req.getHeader("X-Factory-Id"));
    }

    @Test
    void fetchFinanceSummary_4xxResponseThrowsIOException() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));

        IOException ex = assertThrows(IOException.class, () -> client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 4, 30),
                5));
        assertTrue(ex.getMessage().contains("403"), ex.getMessage());
    }

    @Test
    void fetchFinanceSummary_rejectsEmptyFactoryId() {
        assertThrows(IllegalArgumentException.class, () -> client.fetchFinanceSummary(
                "",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 4, 30),
                5));
    }

    @Test
    void fetchFinanceSummary_rejectsInvertedDateRange() {
        assertThrows(IllegalArgumentException.class, () -> client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 4, 30),
                LocalDate.of(2025, 4, 1),
                5));
    }

    @Test
    void fetchFinanceSummary_rejectsInvalidTopN() {
        assertThrows(IllegalArgumentException.class, () -> client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 4, 30),
                0));
        assertThrows(IllegalArgumentException.class, () -> client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 4, 30),
                101));
    }

    @Test
    void fetchFinanceSummary_noSecretStillSendsRequest() throws Exception {
        ReflectionTestUtils.setField(client, "internalSecret", "");
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.fetchFinanceSummary(
                "F001",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 4, 30),
                5);

        RecordedRequest req = server.takeRequest();
        assertNull(req.getHeader("X-Internal-Secret"));
        assertNull(req.getHeader("X-Factory-Id"));
    }

    // =========================================================================
    // fetchTieredIntentAnswer — 2026-07-08 clarification-loop v1 session_id
    // overload (4-arg). Mirrors the RecordedRequest body-inspection pattern
    // used elsewhere in this file.
    // =========================================================================

    @Test
    void fetchTieredIntentAnswer_4arg_withSessionId_includesSessionIdInBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"delegate\":false}"));

        client.fetchTieredIntentAnswer("F001", "最近两个月", "restaurant_peak_month_gold", "sess-abc-123");

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"session_id\":\"sess-abc-123\""), body);
        assertTrue(body.contains("\"factory_id\":\"F001\""), body);
        assertTrue(body.contains("\"query\":\"最近两个月\""), body);
    }

    @Test
    void fetchTieredIntentAnswer_4arg_withNullSessionId_omitsSessionIdField() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"delegate\":false}"));

        client.fetchTieredIntentAnswer("F001", "营收趋势", "restaurant_revenue_trend_gold", null);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertFalse(body.contains("session_id"), body);
    }

    @Test
    void fetchTieredIntentAnswer_4arg_withBlankSessionId_omitsSessionIdField() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"delegate\":false}"));

        client.fetchTieredIntentAnswer("F001", "营收趋势", "restaurant_revenue_trend_gold", "   ");

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertFalse(body.contains("session_id"), body);
    }

    @Test
    void fetchTieredIntentAnswer_3arg_overload_stillOmitsSessionIdField() throws Exception {
        // The pre-existing 3-arg overload (used by every call site that has
        // no session id available) must produce a byte-identical request
        // body to before this feature existed -- no session_id key at all.
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"delegate\":false}"));

        client.fetchTieredIntentAnswer("F001", "营收趋势", "restaurant_revenue_trend_gold");

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertFalse(body.contains("session_id"), body);
        assertTrue(body.contains("\"factory_id\":\"F001\""), body);
    }
}
