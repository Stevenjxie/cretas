package com.cretas.aims.client;

import com.cretas.aims.client.RestaurantAgentRuntimeClient.TrustedContext;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.UpstreamHttpException;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.UpstreamStream;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestaurantAgentRuntimeClientTest {

    private MockWebServer server;
    private ObjectMapper objectMapper;
    private RestaurantAgentRuntimeClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        client = newClient("test-internal-secret");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void postUsesExactInternalPathTrustedHeadersAndStrictBody() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("X-Agent-Run-Id", "00000000-0000-0000-0000-000000000001")
                .setBody("id: 1\nevent: agent.event.v1\ndata: {}\n\n"));

        try (UpstreamStream ignored = client.openStartStream(request(), context())) {
            RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(recorded).isNotNull();
            assertThat(recorded.getMethod()).isEqualTo("POST");
            assertThat(recorded.getPath()).isEqualTo("/api/internal/smartbi/agent/runs");
            assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo("test-internal-secret");
            assertThat(recorded.getHeader("X-Factory-Id")).isEqualTo("R001");
            assertThat(recorded.getHeader("X-User-Id")).isEqualTo("42");
            assertThat(recorded.getHeader("X-User-Role")).isEqualTo("restaurant_owner");
            assertThat(recorded.getHeader("X-Business-Type")).isEqualTo("RESTAURANT");
            assertThat(recorded.getHeader("X-Correlation-ID")).isEqualTo("corr-001");

            Map<String, Object> body = objectMapper.readValue(
                    recorded.getBody().readUtf8(), new TypeReference<>() { });
            assertThat(body.keySet()).isEqualTo(Set.of(
                    "schemaVersion", "routeCode", "startDate", "endDate",
                    "storeTopN", "dishTopN"));
            assertThat(body).doesNotContainKeys(
                    "factoryId", "factory_id", "userId", "role", "businessType");
        }
    }

    @Test
    void redirectIsNeverFollowed() {
        server.enqueue(new MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/attacker")));

        assertThatThrownBy(() -> client.openStartStream(request(), context()))
                .isInstanceOf(UpstreamHttpException.class)
                .extracting(ex -> ((UpstreamHttpException) ex).getStatusCode())
                .isEqualTo(307);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void successfulSseRequiresCanonicalRunHeader() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("id: 1\nevent: agent.event.v1\ndata: {}\n\n"));
        assertThatThrownBy(() -> client.openStartStream(request(), context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("omitted X-Agent-Run-Id");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("X-Agent-Run-Id", "not-a-uuid")
                .setBody("id: 1\nevent: agent.event.v1\ndata: {}\n\n"));
        assertThatThrownBy(() -> client.openStartStream(request(), context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("invalid run id");
    }

    @Test
    void blankSecretFailsBeforeAnyNetworkCall() throws Exception {
        RestaurantAgentRuntimeClient blankSecretClient = newClient("  ");

        assertThatThrownBy(() -> blankSecretClient.openStartStream(request(), context()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void replayUsesExactPathAfterSequenceAndValidatesEventOrdering() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"schemaVersion":"1.0","runId":"00000000-0000-0000-0000-000000000001",
                         "state":"COMPLETED","routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION",
                         "nextEventSequence":8,"events":[
                           {"schemaVersion":"1.0","runId":"00000000-0000-0000-0000-000000000001",
                            "sequence":8,"eventType":"RUN_COMPLETED","stepId":null,"toolName":null,
                            "payload":{"outcomeStatus":"COMPLETE"}}],
                         "terminalOutcome":{"status":"COMPLETE"},"failureCode":null}
                        """));

        RestaurantAgentRunReplayResponse replay = client.replay(runId, 7, context());

        assertThat(replay.getState()).isEqualTo("COMPLETED");
        assertThat(replay.getEvents()).hasSize(1);
        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded.getPath()).isEqualTo(
                "/api/internal/smartbi/agent/runs/00000000-0000-0000-0000-000000000001/events?afterSequence=7");
    }

    @Test
    void eventFrameMustKeepIdAndSequenceAligned() {
        String data = """
                {"schemaVersion":"1.0","runId":"00000000-0000-0000-0000-000000000001",
                 "sequence":2,"eventType":"RUN_STARTED","stepId":null,"toolName":null,"payload":{}}
                """;

        assertThatThrownBy(() -> client.validateEventFrame(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "1", data))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void replayRejectsTamperedStateAndCursor() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(replayBody("INVENTED", 8, 8)));
        assertThatThrownBy(() -> client.replay(runId, 7, context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("replay contract");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(replayBody("COMPLETED", 7, 8)));
        assertThatThrownBy(() -> client.replay(runId, 7, context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("replay cursor");
    }

    @Test
    void replayAllowsCursorPastCurrentTailWhenNoEventsAreReturned() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"schemaVersion":"1.0","runId":"00000000-0000-0000-0000-000000000001",
                         "state":"RUNNING","routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION",
                         "nextEventSequence":8,"events":[],"terminalOutcome":null,"failureCode":null}
                        """));

        RestaurantAgentRunReplayResponse replay = client.replay(runId, 100, context());

        assertThat(replay.getNextEventSequence()).isEqualTo(8);
        assertThat(replay.getEvents()).isEmpty();
    }

    private String replayBody(String state, long nextSequence, long eventSequence) {
        return """
                {"schemaVersion":"1.0","runId":"00000000-0000-0000-0000-000000000001",
                 "state":"%s","routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION",
                 "nextEventSequence":%d,"events":[
                   {"schemaVersion":"1.0","runId":"00000000-0000-0000-0000-000000000001",
                    "sequence":%d,"eventType":"RUN_COMPLETED","stepId":null,"toolName":null,
                    "payload":{}}],"terminalOutcome":null,"failureCode":null}
                """.formatted(state, nextSequence, eventSequence);
    }

    private RestaurantAgentRuntimeClient newClient(String secret) {
        return new RestaurantAgentRuntimeClient(
                new OkHttpClient.Builder().build(),
                server.url("/").toString(),
                secret,
                objectMapper);
    }

    private RestaurantAgentRunStartRequest request() {
        RestaurantAgentRunStartRequest request = new RestaurantAgentRunStartRequest();
        request.setSchemaVersion(RestaurantAgentRunStartRequest.SCHEMA_VERSION);
        request.setRouteCode(RestaurantAgentRunStartRequest.ROUTE_CODE);
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 7, 18));
        return request;
    }

    private TrustedContext context() {
        return new TrustedContext(
                "R001", "42", "restaurant_owner", "RESTAURANT", "corr-001");
    }
}
