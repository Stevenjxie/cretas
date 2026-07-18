package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dto.python.PythonSectionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PythonSmartBIClient owner-action egress contract")
class PythonSmartBIClientOwnerActionTest {

    private MockWebServer mockServer;
    private PythonSmartBIClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        objectMapper = new ObjectMapper();

        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        String baseUrl = mockServer.url("/").toString();
        config.setUrl(baseUrl.substring(0, baseUrl.length() - 1));
        config.setConnectTimeout(250);
        config.setTimeout(250);
        config.setMaxRetries(3);

        PythonServiceCircuitBreaker breaker = new PythonServiceCircuitBreaker();
        setBreakerField(breaker, "failureThreshold", 100);
        setBreakerField(breaker, "openDurationMs", 100L);
        setBreakerField(breaker, "halfOpenMaxCalls", 2);
        setBreakerField(breaker, "successThresholdInHalfOpen", 2);

        client = new PythonSmartBIClient(
                config,
                new OkHttpClient.Builder().build(),
                objectMapper,
                breaker);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void sendsExactInternalUrlTenantHeaderAndMinimalSnakeCaseBody() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"data\":{\"answer\":\"ok\"}}"));

        Map<String, Object> businessRequest = new LinkedHashMap<>();
        businessRequest.put("message", "今天先做什么？");
        businessRequest.put("session_id", "owner-001");
        businessRequest.put("demo_scenario", "revenue_growth");
        businessRequest.put("store_name", "测试门店");
        businessRequest.put("sub_sector", "中餐");
        businessRequest.put("period", "this_week");
        businessRequest.put("factoryId", "ATTACKER-TENANT");
        businessRequest.put("raw", Map.of("should", "not leave Java"));

        Map<String, Object> response = client.askRestaurantOwnerActionChat("F-OWNER", businessRequest);

        assertThat(response).containsEntry("success", true);
        RecordedRequest recorded = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath())
                .isEqualTo("/api/smartbi/restaurant/sections/owner-action-chat");
        assertThat(recorded.getHeader("X-Internal-Secret")).isNotBlank();
        assertThat(recorded.getHeader("X-Factory-Id")).isEqualTo("F-OWNER");

        Map<String, Object> body = objectMapper.readValue(
                recorded.getBody().readUtf8(), new TypeReference<>() {});
        assertThat(body.keySet()).isEqualTo(Set.of(
                "factory_id",
                "message",
                "session_id",
                "demo_scenario",
                "store_name",
                "sub_sector",
                "period"));
        assertThat(body).containsEntry("factory_id", "F-OWNER");
        assertThat(body).doesNotContainKeys("factoryId", "raw");
    }

    @Test
    void ownerActionFiveHundredIsNotRetried() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("sensitive downstream detail"));

        Map<String, Object> response = client.askRestaurantOwnerActionChat(
                "F-OWNER", Map.of("message", "今天先做什么？"));

        assertThat(response).containsEntry("success", false);
        assertThat(response.get("message").toString())
                .doesNotContain("sensitive downstream detail");
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void ownerActionTimeoutIsNotRetried() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"success\":true}")
                .setBodyDelay(1, TimeUnit.SECONDS));

        Map<String, Object> response = client.askRestaurantOwnerActionChat(
                "F-OWNER", Map.of("message", "今天先做什么？"));

        assertThat(response).containsEntry("success", false);
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void otherSmartBiCallsRetainConfiguredRetryBehavior() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("temporary"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"sectionName\":\"diagnostics\",\"status\":\"ok\",\"data\":{}}"));

        Optional<?> response = client.callSection(
                "restaurant",
                "diagnostics",
                PythonSectionRequest.builder().factoryId("F-OWNER").build());

        assertThat(response).isPresent();
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    private static void setBreakerField(
            PythonServiceCircuitBreaker breaker,
            String fieldName,
            Object value) throws Exception {
        Field field = PythonServiceCircuitBreaker.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(breaker, value);
    }
}
