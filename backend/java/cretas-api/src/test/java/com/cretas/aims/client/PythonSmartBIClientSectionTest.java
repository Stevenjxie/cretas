package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dto.python.PythonRestaurantSectionRequest;
import com.cretas.aims.dto.python.PythonRestaurantSectionResponse;
import com.cretas.aims.dto.python.PythonSectionRequest;
import com.cretas.aims.dto.python.PythonSectionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PythonSmartBIClient#callSection(String, String, PythonSectionRequest)}
 * and {@link PythonSmartBIClient#callRestaurantSection(String, PythonRestaurantSectionRequest)}.
 *
 * <p>Uses OkHttp {@link MockWebServer} — no Spring context, no real Python
 * backend. Circuit breaker thresholds are injected via reflection to keep
 * the test deterministic and fast.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Happy path: {@code status="ok"}, Optional.present, URL path + JSON body correct</li>
 *   <li>{@link PythonSmartBIClient#callRestaurantSection} delegation uses domain="restaurant"</li>
 *   <li>HTTP 500 → Optional.empty (after retries exhausted)</li>
 *   <li>{@code config.enabled=false} → Optional.empty, no network call</li>
 *   <li>{@code status="skipped"} with HTTP 200 → Optional.present (success=false, warnings populated)</li>
 * </ul>
 *
 * @since 2026-04-10
 */
@DisplayName("PythonSmartBIClient.callSection() unit tests")
class PythonSmartBIClientSectionTest {

    private MockWebServer mockServer;
    private PythonSmartBIClient client;
    private PythonSmartBIConfig config;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        mapper = new ObjectMapper();

        config = new PythonSmartBIConfig();
        config.setEnabled(true);
        // mockServer.url("/") returns http://localhost:PORT/ — strip trailing
        // slash so getFullUrl() produces clean URLs.
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        config.setUrl(baseUrl);
        config.setConnectTimeout(2000);
        config.setTimeout(5000);
        config.setMaxRetries(0);  // no retries in unit test — keeps failures fast

        OkHttpClient baseClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        PythonServiceCircuitBreaker breaker = new PythonServiceCircuitBreaker();
        // Inject @Value fields via reflection (normally set by Spring). Use
        // high threshold + short duration so one failure doesn't trip the
        // breaker mid-test (each test gets a fresh breaker anyway).
        setBreakerField(breaker, "failureThreshold", 100);
        setBreakerField(breaker, "openDurationMs", 100L);
        setBreakerField(breaker, "halfOpenMaxCalls", 2);
        setBreakerField(breaker, "successThresholdInHalfOpen", 2);

        client = new PythonSmartBIClient(
                config, baseClient, mapper, breaker, "test-internal-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("callSection returns OK response on HTTP 200, URL + JSON body correct")
    void callSection_returnsOkResponseWhenServerReturns200() throws Exception {
        String responseJson = "{"
                + "\"success\": true,"
                + "\"sectionName\": \"cost_rigidity\","
                + "\"status\": \"ok\","
                + "\"data\": {\"costRigidity\": 0.561, \"severity\": \"critical\"},"
                + "\"warnings\": [],"
                + "\"cacheKey\": \"cost_rigidity:F-TEST:live:current\","
                + "\"computedAtMs\": 42,"
                + "\"fromCache\": false"
                + "}";

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseJson));

        Map<String, Object> currentPeriod = new HashMap<>();
        currentPeriod.put("revenue", 731048);
        currentPeriod.put("labor_cost", 237660);
        Map<String, Object> previousPeriod = new HashMap<>();
        previousPeriod.put("revenue", 1390503);
        previousPeriod.put("labor_cost", 323805);
        Map<String, Object> financialData = new HashMap<>();
        financialData.put("current", currentPeriod);
        financialData.put("previous", previousPeriod);
        Map<String, Object> params = new HashMap<>();
        params.put("financial_data", financialData);

        PythonSectionRequest request = PythonSectionRequest.builder()
                .factoryId("F-TEST")
                .subSector("火锅")
                .params(params)
                .build();

        Optional<PythonSectionResponse> result = client.callSection("restaurant", "cost_rigidity", request);

        assertThat(result).isPresent();
        PythonSectionResponse response = result.get();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo("ok");
        assertThat(response.getSectionName()).isEqualTo("cost_rigidity");
        assertThat(response.getData())
                .containsEntry("severity", "critical");
        assertThat(((Number) response.getData().get("costRigidity")).doubleValue())
                .isEqualTo(0.561);
        assertThat(response.getFromCache()).isFalse();
        assertThat(response.getCacheKey()).isEqualTo("cost_rigidity:F-TEST:live:current");
        assertThat(response.getComputedAtMs()).isEqualTo(42L);

        // Verify the URL and request body
        RecordedRequest recorded = mockServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/smartbi/restaurant/sections/cost_rigidity");
        assertThat(recorded.getMethod()).isEqualTo("POST");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"factory_id\":\"F-TEST\"");
        assertThat(body).contains("\"sub_sector\":\"火锅\"");
        assertThat(body).contains("\"financial_data\"");
        // X-Internal-Secret interceptor should auto-add the header
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("test-internal-secret");
        assertThat(recorded.getHeader("X-Factory-Id")).isEqualTo("F-TEST");
    }

    // ── Restaurant wrapper delegation ───────────────────────────────────

    @Test
    @DisplayName("callRestaurantSection delegates to callSection with domain=\"restaurant\"")
    void callRestaurantSection_wrapsCallSectionWithRestaurantDomain() throws Exception {
        String responseJson = "{"
                + "\"success\": true,"
                + "\"sectionName\": \"diagnostics\","
                + "\"status\": \"ok\","
                + "\"data\": {\"criticalCount\": 1},"
                + "\"warnings\": [],"
                + "\"cacheKey\": \"diagnostics:F-TEST:live:current\","
                + "\"computedAtMs\": 12,"
                + "\"fromCache\": false"
                + "}";

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseJson));

        PythonRestaurantSectionRequest request = PythonRestaurantSectionRequest.builder()
                .factoryId("F-TEST")
                .subSector("火锅")
                .build();

        Optional<PythonRestaurantSectionResponse> result = client.callRestaurantSection("diagnostics", request);

        assertThat(result).isPresent();
        assertThat(result.get().isSuccess()).isTrue();
        assertThat(result.get().getSectionName()).isEqualTo("diagnostics");
        assertThat(((Number) result.get().getData().get("criticalCount")).intValue()).isEqualTo(1);

        RecordedRequest recorded = mockServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/smartbi/restaurant/sections/diagnostics");
        assertThat(recorded.getMethod()).isEqualTo("POST");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"factory_id\":\"F-TEST\"");
        assertThat(body).contains("\"sub_sector\":\"火锅\"");
        assertThat(recorded.getHeader("X-Factory-Id")).isEqualTo("F-TEST");
    }

    @Test
    @DisplayName("blank internal secret and blank factory fail before network")
    void callSection_failsClosedBeforeNetworkForMissingCredentials() throws Exception {
        PythonServiceCircuitBreaker breaker = new PythonServiceCircuitBreaker();
        setBreakerField(breaker, "failureThreshold", 100);
        setBreakerField(breaker, "openDurationMs", 100L);
        setBreakerField(breaker, "halfOpenMaxCalls", 2);
        setBreakerField(breaker, "successThresholdInHalfOpen", 2);
        PythonSmartBIClient blankSecretClient = new PythonSmartBIClient(
                config,
                new OkHttpClient(),
                mapper,
                breaker,
                "   ");

        Optional<PythonSectionResponse> blankSecretResult = blankSecretClient.callSection(
                "restaurant",
                "diagnostics",
                PythonSectionRequest.builder().factoryId("F-TEST").build());
        Optional<PythonSectionResponse> blankFactoryResult = client.callSection(
                "restaurant",
                "diagnostics",
                PythonSectionRequest.builder().factoryId(" ").build());

        assertThat(blankSecretResult).isEmpty();
        assertThat(blankFactoryResult).isEmpty();
        assertThat(mockServer.getRequestCount()).isZero();
    }

    // ── Error path: server 500 ──────────────────────────────────────────

    @Test
    @DisplayName("callSection returns empty on HTTP 500")
    void callSection_returnsEmptyOnServerError() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        PythonSectionRequest request = PythonSectionRequest.builder()
                .factoryId("F-TEST")
                .subSector("火锅")
                .build();

        Optional<PythonSectionResponse> result = client.callSection("restaurant", "cost_rigidity", request);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("generic SmartBI section calls retain configured retry behavior")
    void callSection_retriesConfiguredTransientFailure() {
        config.setMaxRetries(1);
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("temporary"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"success":true,"sectionName":"diagnostics","status":"ok","data":{}}
                        """));

        Optional<PythonSectionResponse> result = client.callSection(
                "restaurant",
                "diagnostics",
                PythonSectionRequest.builder().factoryId("F-TEST").build());

        assertThat(result).isPresent();
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    // ── Service disabled → short-circuit ─────────────────────────────────

    @Test
    @DisplayName("callSection returns empty when config.enabled=false, no network call")
    void callSection_returnsEmptyWhenServiceDisabled() throws Exception {
        PythonSmartBIConfig disabledConfig = new PythonSmartBIConfig();
        disabledConfig.setEnabled(false);
        disabledConfig.setUrl("http://unused");
        disabledConfig.setConnectTimeout(2000);
        disabledConfig.setTimeout(5000);
        disabledConfig.setMaxRetries(0);

        PythonServiceCircuitBreaker freshBreaker = new PythonServiceCircuitBreaker();
        setBreakerField(freshBreaker, "failureThreshold", 100);
        setBreakerField(freshBreaker, "openDurationMs", 100L);
        setBreakerField(freshBreaker, "halfOpenMaxCalls", 2);
        setBreakerField(freshBreaker, "successThresholdInHalfOpen", 2);

        PythonSmartBIClient disabledClient = new PythonSmartBIClient(
                disabledConfig,
                new OkHttpClient(),
                mapper,
                freshBreaker,
                "test-internal-secret");

        PythonSectionRequest request = PythonSectionRequest.builder()
                .factoryId("F-TEST")
                .subSector("火锅")
                .build();

        Optional<PythonSectionResponse> result = disabledClient.callSection("restaurant", "cost_rigidity", request);

        assertThat(result).isEmpty();
        // No request should have been dispatched to the mock server
        assertThat(mockServer.getRequestCount()).isZero();
    }

    // ── Section returned status="skipped" (HTTP 200) ─────────────────────

    @Test
    @DisplayName("callSection returns present for status=\"skipped\" (HTTP 200)")
    void callSection_returnsPresentOnSkippedResponse() throws Exception {
        // Skipped responses are still parsed successfully (HTTP 200) — the
        // client returns them wrapped in Optional.of(...), not empty. Only
        // HTTP errors / circuit breaker open map to Optional.empty().
        String skipJson = "{"
                + "\"success\": false,"
                + "\"sectionName\": \"channel_margin\","
                + "\"status\": \"skipped\","
                + "\"data\": {},"
                + "\"warnings\": [\"未提供 POS DataFrame\"],"
                + "\"cacheKey\": \"channel_margin:F-TEST:live:current\","
                + "\"computedAtMs\": 1,"
                + "\"fromCache\": false"
                + "}";

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(skipJson));

        PythonSectionRequest request = PythonSectionRequest.builder()
                .factoryId("F-TEST")
                .subSector("火锅")
                .build();

        Optional<PythonSectionResponse> result = client.callSection("restaurant", "channel_margin", request);

        assertThat(result).isPresent();
        assertThat(result.get().isSuccess()).isFalse();
        assertThat(result.get().getStatus()).isEqualTo("skipped");
        assertThat(result.get().getWarnings()).contains("未提供 POS DataFrame");
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private static void setBreakerField(PythonServiceCircuitBreaker breaker,
                                        String fieldName, Object value) throws Exception {
        Field field = PythonServiceCircuitBreaker.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(breaker, value);
    }
}
