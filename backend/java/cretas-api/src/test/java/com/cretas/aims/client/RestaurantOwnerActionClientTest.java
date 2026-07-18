package com.cretas.aims.client;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.gateway.ToolEgressPermit;
import com.cretas.aims.client.RestaurantOwnerActionClient.OwnerActionRequest;
import com.cretas.aims.client.RestaurantOwnerActionClient.OwnerActionUnavailableException;
import com.cretas.aims.client.RestaurantOwnerActionClient.TrustedContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestaurantOwnerActionClientTest {

    private MockWebServer server;
    private ObjectMapper objectMapper;
    private RestaurantOwnerActionClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        objectMapper = new ObjectMapper();
        client = newClient("internal-secret");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsOneExactRequestWithTrustedHeadersMinimalBodyAndAllowlistedResponse() throws Exception {
        server.enqueue(jsonResponse("""
                {"success":true,"data":{
                  "answer":"先盯晚高峰","responseText":"先盯晚高峰",
                  "sessionId":"s-1","scenario":"staffing","charts":[],
                  "roleActionPlan":[],"followUpSuggestions":[],
                  "ownerDecisionPage":{},"dataReadiness":{},"unknownSecret":"drop-me"}}
                """));

        Map<String, Object> data = client.advise(
                call("req-1"),
                permit("req-1", Instant.now().plusSeconds(30),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                context("req-1"),
                request());

        assertThat(data).containsEntry("answer", "先盯晚高峰");
        assertThat(data).doesNotContainKey("unknownSecret");
        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo(
                "/api/smartbi/restaurant/sections/owner-action-chat");
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("internal-secret");
        assertThat(recorded.getHeader("X-Factory-Id")).isEqualTo("RES-1");
        assertThat(recorded.getHeader("X-User-Id")).isEqualTo("42");
        assertThat(recorded.getHeader("X-User-Role")).isEqualTo("restaurant_owner");
        assertThat(recorded.getHeader("X-Business-Type")).isEqualTo("BRANCH");
        assertThat(recorded.getHeader("X-Correlation-ID")).isEqualTo("req-1");
        Map<String, Object> body = objectMapper.readValue(
                recorded.getBody().readUtf8(), new TypeReference<>() { });
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "factory_id", "message", "session_id", "demo_scenario",
                "store_name", "sub_sector", "period");
        assertThat(body).containsEntry("factory_id", "RES-1");
        assertThat(body).doesNotContainKeys(
                "factoryId", "userId", "role", "businessType", "raw");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void blankSecretAndInvalidPermitsFailBeforeNetwork() throws Exception {
        RestaurantOwnerActionClient blank = newClient("  ");
        assertThatThrownBy(() -> blank.advise(
                call("blank"),
                permit("blank", Instant.now().plusSeconds(30),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                context("blank"),
                request())).isInstanceOf(OwnerActionUnavailableException.class);

        assertZeroNetworkFailure(null, call("missing"));
        assertZeroNetworkFailure(
                permit("different", Instant.now().plusSeconds(30),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                call("wrong-request"));
        assertZeroNetworkFailure(
                permit("wrong-version", "1.0.0", Instant.now().plusSeconds(30),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                call("wrong-version"));
        assertZeroNetworkFailure(
                permit("wrong-destination", Instant.now().plusSeconds(30), "other.destination"),
                call("wrong-destination"));
        assertZeroNetworkFailure(
                permit("expired", Instant.now().minusSeconds(1),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                call("expired"));
        assertZeroNetworkFailure(
                permit("wrong-name", Instant.now().plusSeconds(30),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                ToolCall.of("wrong-name", "another_tool", "{}"));

        assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void redirectsAndServerErrorsAreNotRetried() {
        server.enqueue(new MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/attacker")));
        assertThatThrownBy(() -> validCall("redirect"))
                .isInstanceOf(OwnerActionUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);

        server.enqueue(new MockResponse().setResponseCode(500).setBody("sensitive detail"));
        assertThatThrownBy(() -> validCall("server-error"))
                .isInstanceOf(OwnerActionUnavailableException.class)
                .hasMessageNotContaining("sensitive detail");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void rejectsWrongContentTypeEmptyUnknownOnlyAndKnownTypeDrift() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("{\"success\":true,\"data\":{\"answer\":\"ok\"}}"));
        assertUnavailable("content-type");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/jsonp")
                .setBody("{\"success\":true,\"data\":{\"answer\":\"ok\"}}"));
        assertUnavailable("jsonp");

        server.enqueue(jsonResponse("{\"success\":true,\"data\":{}}"));
        assertUnavailable("empty");

        server.enqueue(jsonResponse(
                "{\"success\":true,\"data\":{\"unknown\":\"only\"}}"));
        assertUnavailable("unknown-only");

        server.enqueue(jsonResponse(
                "{\"success\":true,\"data\":{\"answer\":\"ok\",\"charts\":{}}}"));
        assertUnavailable("wrong-array");

        server.enqueue(jsonResponse(
                "{\"success\":true,\"data\":{\"answer\":[],\"responseText\":\"ok\"}}"));
        assertUnavailable("wrong-text");

        server.enqueue(jsonResponse(
                "{\"success\":true,\"data\":{\"answer\":\"ok\",\"dataReadiness\":[]}}"));
        assertUnavailable("wrong-object");
    }

    @Test
    void rejectsMalformedAndOversizedResponses() {
        server.enqueue(jsonResponse("not-json"));
        assertUnavailable("malformed");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("x".repeat(1_048_577)));
        assertUnavailable("oversized");
    }

    private void assertZeroNetworkFailure(ToolEgressPermit permit, ToolCall actualCall) {
        assertThatThrownBy(() -> client.advise(actualCall, permit, context(actualCall.getId()), request()))
                .isInstanceOf(SecurityException.class);
    }

    private void assertUnavailable(String id) {
        assertThatThrownBy(() -> validCall(id))
                .isInstanceOf(OwnerActionUnavailableException.class);
    }

    private Map<String, Object> validCall(String id) throws Exception {
        return client.advise(
                call(id),
                permit(id, Instant.now().plusSeconds(30),
                        RestaurantOwnerActionClient.DESTINATION_ID),
                context(id),
                request());
    }

    private RestaurantOwnerActionClient newClient(String secret) {
        return new RestaurantOwnerActionClient(
                new OkHttpClient.Builder().build(),
                server.url("/").toString(),
                secret,
                objectMapper);
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body);
    }

    private ToolCall call(String id) {
        return ToolCall.of(id, RestaurantOwnerActionClient.TOOL_NAME, "{}");
    }

    private TrustedContext context(String correlationId) {
        return new TrustedContext(
                "RES-1", "42", "restaurant_owner", "BRANCH", correlationId);
    }

    private OwnerActionRequest request() {
        return new OwnerActionRequest(
                "今天先做什么？",
                "session-1",
                "staffing",
                "测试门店",
                "中餐",
                "this_week");
    }

    private ToolEgressPermit permit(
            String requestId,
            Instant deadline,
            String destination) throws Exception {
        return permit(
                requestId,
                RestaurantOwnerActionClient.TOOL_VERSION,
                deadline,
                destination);
    }

    private ToolEgressPermit permit(
            String requestId,
            String version,
            Instant deadline,
            String destination) throws Exception {
        Constructor<ToolEgressPermit> constructor = ToolEgressPermit.class
                .getDeclaredConstructor(
                        String.class, String.class, String.class, Instant.class, Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                RestaurantOwnerActionClient.TOOL_NAME,
                version,
                requestId,
                deadline,
                Set.of(destination));
    }
}
