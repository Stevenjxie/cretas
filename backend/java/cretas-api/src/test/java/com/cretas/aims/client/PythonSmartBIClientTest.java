package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
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
        config.setMaxRetries(0);
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
}
