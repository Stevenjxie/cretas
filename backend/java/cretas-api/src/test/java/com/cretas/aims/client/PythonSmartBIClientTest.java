package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.dto.python.PythonServiceHealthResponse;
import com.cretas.aims.dto.smartbi.PythonForecastResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PythonSmartBIClientTest {

    private MockWebServer server;
    private PythonSmartBIClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        // MockWebServer URL without trailing slash
        config.setUrl(server.url("").toString().replaceAll("/$", ""));
        config.setForecastEndpoint("/api/forecast/predict");
        config.setMaxRetries(3);
        config.setTimeout(5000);
        config.setConnectTimeout(5000);

        // Use a real OkHttpClient so the client's newBuilder() chain works
        OkHttpClient baseHttpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        // PythonServiceCircuitBreaker is a plain @Component — instantiate directly.
        // @Value fields use defaults (threshold=5, openDuration=30000) which is fine
        // for unit tests since we never exhaust failure threshold.
        PythonServiceCircuitBreaker circuitBreaker = new PythonServiceCircuitBreaker();

        client = new PythonSmartBIClient(
                config, baseHttpClient, mapper, circuitBreaker, "test-internal-secret");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void forecastWithData_sendsCorrectPayload() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"algorithm\":\"moving_average\","
                        + "\"predictions\":[100.0,110.0,120.0],"
                        + "\"lowerBound\":[90.0,99.0,108.0],"
                        + "\"upperBound\":[110.0,121.0,132.0]}"));

        List<Double> data = List.of(50.0, 60.0, 70.0, 80.0, 90.0);
        PythonForecastResponse resp = client.forecastWithData(data, 3, "auto");

        // Verify request payload
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/forecast/predict", req.getPath());

        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertEquals(5, body.get("data").size());
        assertEquals(50.0, body.get("data").get(0).asDouble(), 0.001);
        assertEquals(3, body.get("periods").asInt());
        assertEquals("auto", body.get("algorithm").asText());
        assertTrue(body.has("confidenceLevel"));
        assertEquals(0.95, body.get("confidenceLevel").asDouble(), 0.001);

        // Verify response deserialization
        assertTrue(resp.isSuccess());
        assertEquals("moving_average", resp.getAlgorithm());
        assertEquals(3, resp.getPredictions().size());
        assertEquals(100.0, resp.getPredictions().get(0), 0.001);
    }

    @Test
    void revenueReport_sendsExactTrustedHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"data\":{}}"));

        Map<String, Object> response = client.callRevenueReport(
                "/api/smartbi/REST-1/revenue-report/prepare",
                Map.of("date_from", "2026-07-01", "date_to", "2026-07-19"),
                "REST-1",
                "restaurant_manager");

        assertNotNull(response);
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/smartbi/REST-1/revenue-report/prepare", request.getPath());
        assertEquals("test-internal-secret", request.getHeader("X-Internal-Secret"));
        assertEquals("REST-1", request.getHeader("X-Factory-Id"));
        assertEquals("restaurant_manager", request.getHeader("X-User-Role"));
    }

    @Test
    void asyncExcelUploadAndPollSendExactFactoryHeaderWithoutQueryAuth() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"uploadId\":73,\"status\":\"PENDING\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"uploadId\":73,\"status\":\"COMPLETED\","
                        + "\"rowCount\":1,\"columnCount\":1}"));

        var response = client.parseExcelViaAsync(
                new MockMultipartFile(
                        "file", "orders.xlsx", "application/vnd.ms-excel", new byte[]{1, 2, 3}),
                "REST-ASYNC",
                0,
                null,
                null);

        assertTrue(response.isSuccess());
        RecordedRequest upload = server.takeRequest();
        RecordedRequest poll = server.takeRequest();
        assertEquals("REST-ASYNC", upload.getHeader("X-Factory-Id"));
        assertEquals("REST-ASYNC", poll.getHeader("X-Factory-Id"));
        assertEquals("test-internal-secret", upload.getHeader("X-Internal-Secret"));
        assertEquals("test-internal-secret", poll.getHeader("X-Internal-Secret"));
        assertEquals("/api/smartbi/excel/auto-parse-status/73", poll.getPath());
        assertTrue(upload.getBody().readUtf8().contains("REST-ASYNC"));
    }

    @Test
    void analyzeGeneralUsesExactRouteTrustedHeadersAndIdentityFreeBody() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"answer\":\"typed answer\","
                        + "\"sessionId\":\"session-1\",\"messageCount\":2}"));

        PythonGeneralAnalysisResponse response = client.analyzeGeneral(
                "FACTORY-1",
                "73",
                PythonGeneralAnalysisRequest.builder()
                        .message("analyze cost")
                        .sessionId("session-1")
                        .enableThinking(true)
                        .thinkingBudget(50)
                        .allowTenantDataFallback(false)
                        .build());

        assertEquals("typed answer", response.getEffectiveAnalysis());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/chat/general-analysis", request.getPath());
        assertEquals("FACTORY-1", request.getHeader("X-Factory-Id"));
        assertEquals("73", request.getHeader("X-User-Id"));
        assertEquals("test-internal-secret", request.getHeader("X-Internal-Secret"));

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("analyze cost", body.get("query").asText());
        assertEquals("session-1", body.get("session_id").asText());
        assertFalse(body.get("allow_tenant_data_fallback").asBoolean());
        assertFalse(body.has("factoryId"));
        assertFalse(body.has("factory_id"));
        assertFalse(body.has("userId"));
        assertFalse(body.has("user_id"));
    }

    @Test
    void typedGeneralAnalysisSendsQueryDataTableAndExactTrustedRole() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"answer\":\"restaurant answer\",\"charts\":[]}"));

        PythonGeneralAnalysisResponse response = client.analyzeGeneral(
                "REST-1",
                "91",
                "restaurant_manager",
                new PythonSmartBIClient.GeneralAnalysisCall(
                        "compare restaurant costs",
                        List.of(Map.of("cost", 12.5)),
                        "restaurant_ops",
                        "session-9",
                        false,
                        0,
                        false));

        assertEquals("restaurant answer", response.getEffectiveAnalysis());
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/chat/general-analysis", request.getPath());
        assertEquals("REST-1", request.getHeader("X-Factory-Id"));
        assertEquals("91", request.getHeader("X-User-Id"));
        assertEquals("restaurant_manager", request.getHeader("X-User-Role"));
        assertEquals("test-internal-secret", request.getHeader("X-Internal-Secret"));
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("compare restaurant costs", body.path("query").asText());
        assertEquals("restaurant_ops", body.path("table_type").asText());
        assertEquals(12.5, body.path("data").get(0).path("cost").asDouble(), 0.001);
        assertFalse(body.path("allow_tenant_data_fallback").asBoolean(true));
        assertFalse(body.has("factory_id"));
        assertFalse(body.has("user_id"));
    }

    @Test
    void generalAnalysisStreamUsesFixedRouteAndTypedBoundedEvents() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody("event: status\ndata: \"正在分析\"\n\n"
                        + "event: chunk\ndata: \"part one\"\n\n"
                        + "event: done\ndata: {\"success\":true,\"answer\":\"part one\","
                        + "\"processingTimeMs\":12}\n\n"));
        List<PythonSmartBIClient.GeneralAnalysisStreamEvent> events =
                new java.util.ArrayList<>();

        client.streamGeneralAnalysis(
                "REST-STREAM",
                "22",
                "finance_manager",
                new PythonSmartBIClient.GeneralAnalysisCall(
                        "analyze stream",
                        List.of(Map.of("amount", 10)),
                        "time_range_cost",
                        null,
                        true,
                        50,
                        false),
                events::add);

        assertEquals(List.of("status", "chunk", "done"),
                events.stream().map(PythonSmartBIClient.GeneralAnalysisStreamEvent::event).toList());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/chat/general-analysis-stream", request.getPath());
        assertEquals("text/event-stream", request.getHeader("Accept"));
        assertEquals("REST-STREAM", request.getHeader("X-Factory-Id"));
        assertEquals("22", request.getHeader("X-User-Id"));
        assertEquals("finance_manager", request.getHeader("X-User-Role"));
        assertEquals("test-internal-secret", request.getHeader("X-Internal-Secret"));
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("analyze stream", body.path("query").asText());
        assertEquals("time_range_cost", body.path("table_type").asText());
        assertFalse(body.path("allow_tenant_data_fallback").asBoolean(true));
    }

    @Test
    void analyzeGeneralOmitsOptionalUserHeaderAndNeverRetriesPost() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"http://internal-service/secret\"}"));

        Exception failure = assertThrows(Exception.class, () -> client.analyzeGeneral(
                "FACTORY-2",
                null,
                PythonGeneralAnalysisRequest.builder()
                        .message("question")
                        .allowTenantDataFallback(false)
                        .build()));

        assertEquals(1, server.getRequestCount());
        RecordedRequest request = server.takeRequest();
        assertNull(request.getHeader("X-User-Id"));
        assertFalse(failure.getMessage().contains("internal-service"));
        assertFalse(failure.getMessage().contains(server.getHostName()));
    }

    @Test
    void analyzeGeneralRejectsSuccessfulButEmptyAnalysis() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"answer\":\"  \",\"aiAnalysis\":null}"));

        Exception failure = assertThrows(Exception.class, () -> client.analyzeGeneral(
                "FACTORY-3",
                "81",
                PythonGeneralAnalysisRequest.builder()
                        .message("question")
                        .allowTenantDataFallback(false)
                        .build()));

        assertEquals("Python SmartBI general analysis is unavailable", failure.getMessage());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void healthOnlyAcceptsTypedHealthyStatus() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"degraded\",\"service\":\"python-services\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"healthy\",\"service\":\"python-services\"}"));

        PythonServiceHealthResponse degraded = client.health();
        PythonServiceHealthResponse healthy = client.health();

        assertFalse(degraded.isHealthy());
        assertTrue(healthy.isHealthy());
        assertEquals("/health", server.takeRequest().getPath());
        assertEquals("/health", server.takeRequest().getPath());
    }
}
