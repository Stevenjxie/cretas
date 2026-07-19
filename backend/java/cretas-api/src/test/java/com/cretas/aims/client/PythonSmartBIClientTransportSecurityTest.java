package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonSmartBIClientTransportSecurityTest {

    private final List<MockWebServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        MDC.clear();
        for (MockWebServer server : servers) {
            server.shutdown();
        }
    }

    @Test
    void redirectsAreNeverFollowedAndNeverLeakInternalSecret() throws Exception {
        for (int status : List.of(301, 302, 307, 308)) {
            MockWebServer origin = startServer();
            MockWebServer destination = startServer();
            origin.enqueue(new MockResponse()
                    .setResponseCode(status)
                    .setHeader("Location", destination.url("/capture")));

            PythonSmartBIClient client = newClient(origin, 3, "redirect-secret");

            assertThatThrownBy(() -> client.forecastWithData(
                    List.of(1.0, 2.0, 3.0), 1, "auto"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP " + status)
                    .hasMessageNotContaining("redirect-secret");
            assertThat(origin.getRequestCount()).isEqualTo(1);
            assertThat(destination.getRequestCount()).isZero();
        }
    }

    @Test
    void crossAuthorityAndBlankSecretFailBeforeNetwork() throws Exception {
        MockWebServer origin = startServer();
        MockWebServer other = startServer();
        PythonSmartBIClient client = newClient(origin, 0, "same-origin-secret");

        Field clientField = PythonSmartBIClient.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        OkHttpClient securedClient = (OkHttpClient) clientField.get(client);
        Request crossAuthority = new Request.Builder().url(other.url("/capture")).get().build();

        assertThatThrownBy(() -> securedClient.newCall(crossAuthority).execute())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("destination is not permitted");
        assertThat(other.getRequestCount()).isZero();

        PythonSmartBIClient blankSecret = newClient(origin, 0, "   ");
        assertThat(blankSecret.callFinancialDashboard(
                "/generate", Map.of("factory_id", "REST-1"), "REST-1"))
                .isNull();
        assertThat(origin.getRequestCount()).isZero();
    }

    @Test
    void sameAuthorityReceivesExactSecretCorrelationAcceptAndTenant() throws Exception {
        MockWebServer origin = startServer();
        origin.enqueue(json(200, "{\"success\":true}"));
        MDC.put("correlationId", "corr-transport-1");
        PythonSmartBIClient client = newClient(origin, 0, "same-origin-secret");

        Map<String, Object> result = client.callFinancialDashboard(
                "/generate", Map.of("factory_id", "REST-1"), "REST-1");

        assertThat(result).containsEntry("success", true);
        RecordedRequest request = origin.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/smartbi/financial-dashboard/generate");
        assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("same-origin-secret");
        assertThat(request.getHeader("X-Correlation-ID")).isEqualTo("corr-transport-1");
        assertThat(request.getHeader("X-Factory-Id")).isEqualTo("REST-1");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
    }

    @Test
    void nonIdempotentPostIsAttemptedOnceButGetCanUseConfiguredRetry() throws Exception {
        MockWebServer postServer = startServer();
        postServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        PythonSmartBIClient postClient = newClient(postServer, 2, "post-secret");

        assertThatThrownBy(() -> postClient.forecastWithData(
                List.of(1.0, 2.0, 3.0), 1, "auto"))
                .isInstanceOf(IOException.class);
        assertThat(postServer.getRequestCount()).isEqualTo(1);

        MockWebServer getServer = startServer();
        getServer.enqueue(new MockResponse().setResponseCode(503));
        getServer.enqueue(json(200, "{\"success\":true,\"report\":{}}"));
        PythonSmartBIClient getClient = newClient(getServer, 1, "get-secret");

        Map<String, Object> response = getClient.getRestaurantHealthCheckReport("REST-1", "2026-07");
        assertThat(response).containsEntry("success", true);
        assertThat(getServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void responseProtocolIsBoundedTypedAndSanitized() throws Exception {
        MockWebServer nonJsonServer = startServer();
        nonJsonServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>not json</html>"));
        PythonSmartBIClient nonJsonClient = newClient(nonJsonServer, 0, "type-secret");
        assertThatThrownBy(() -> nonJsonClient.forecastWithData(
                List.of(1.0, 2.0, 3.0), 1, "auto"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported response");

        MockWebServer oversizedServer = startServer();
        oversizedServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setHeader("Content-Length", 256L * 1024L * 1024L + 1L));
        PythonSmartBIClient oversizedClient = newClient(oversizedServer, 0, "size-secret");
        assertThatThrownBy(() -> oversizedClient.forecastWithData(
                List.of(1.0, 2.0, 3.0), 1, "auto"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("configured limit");

        MockWebServer errorServer = startServer();
        errorServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setChunkedBody("TOP-SECRET-UPSTREAM-BODY".repeat(1024), 257));
        PythonSmartBIClient errorClient = newClient(errorServer, 0, "transport-secret");
        assertThatThrownBy(() -> errorClient.forecastWithData(
                List.of(1.0, 2.0, 3.0), 1, "auto"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("TOP-SECRET")
                .hasMessageNotContaining("transport-secret");

        PythonSmartBIClient.BoundedInputStream chunkedBoundary =
                new PythonSmartBIClient.BoundedInputStream(
                        new ByteArrayInputStream(new byte[9]), 8L);
        try {
            assertThatThrownBy(chunkedBoundary::readAllBytes)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("configured limit");
        } finally {
            chunkedBoundary.closeDelegate();
        }
    }

    @Test
    void generalAnalysisStreamIsSingleAttemptBoundedTypedAndSanitized() throws Exception {
        MockWebServer errorServer = startServer();
        errorServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("PRIVATE-DOWNSTREAM-DETAIL"));
        PythonSmartBIClient errorClient = newClient(errorServer, 3, "stream-secret");

        assertThatThrownBy(() -> errorClient.streamGeneralAnalysis(
                "REST-1", "7", "restaurant_manager", streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable")
                .hasMessageNotContaining("PRIVATE-DOWNSTREAM-DETAIL")
                .hasMessageNotContaining("stream-secret");
        assertThat(errorServer.getRequestCount()).isEqualTo(1);

        MockWebServer wrongTypeServer = startServer();
        wrongTypeServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true}"));
        assertThatThrownBy(() -> newClient(wrongTypeServer, 0, "type-secret")
                .streamGeneralAnalysis(
                        "REST-1", null, null, streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable");

        MockWebServer incompleteServer = startServer();
        incompleteServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: chunk\ndata: \"partial only\"\n\n"));
        assertThatThrownBy(() -> newClient(incompleteServer, 0, "done-secret")
                .streamGeneralAnalysis(
                        "REST-1", null, null, streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable");

        MockWebServer oversizedServer = startServer();
        oversizedServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: done\ndata: {\"success\":true,\"answer\":\"ok\"}\n\n")
                .setHeader("Content-Length", 16L * 1024L * 1024L + 1L));
        assertThatThrownBy(() -> newClient(oversizedServer, 0, "size-secret")
                .streamGeneralAnalysis(
                        "REST-1", null, null, streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable");
    }

    @Test
    void generalAnalysisStreamNeverFollowsRedirectOrAcceptsUnknownAndErrorEvents()
            throws Exception {
        MockWebServer redirectOrigin = startServer();
        MockWebServer redirectDestination = startServer();
        redirectOrigin.enqueue(new MockResponse()
                .setResponseCode(307)
                .setHeader("Location", redirectDestination.url("/capture")));
        assertThatThrownBy(() -> newClient(redirectOrigin, 3, "redirect-stream-secret")
                .streamGeneralAnalysis(
                        "REST-1", null, null, streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable")
                .hasMessageNotContaining("redirect-stream-secret");
        assertThat(redirectOrigin.getRequestCount()).isEqualTo(1);
        assertThat(redirectDestination.getRequestCount()).isZero();

        MockWebServer unknownEventServer = startServer();
        unknownEventServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: private-debug\ndata: \"TOP-SECRET\"\n\n"));
        assertThatThrownBy(() -> newClient(unknownEventServer, 0, "unknown-secret")
                .streamGeneralAnalysis(
                        "REST-1", null, null, streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable")
                .hasMessageNotContaining("TOP-SECRET");

        MockWebServer errorEventServer = startServer();
        errorEventServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: error\ndata: \"PRIVATE-PYTHON-ERROR\"\n\n"));
        assertThatThrownBy(() -> newClient(errorEventServer, 0, "event-secret")
                .streamGeneralAnalysis(
                        "REST-1", null, null, streamRequest(), event -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Python SmartBI general analysis stream is unavailable")
                .hasMessageNotContaining("PRIVATE-PYTHON-ERROR")
                .hasMessageNotContaining("event-secret");
    }

    @Test
    void downstreamConsumerDisconnectDoesNotPoisonPythonCircuitBreaker() throws Exception {
        MockWebServer origin = startServer();
        origin.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: chunk\ndata: \"answer\"\n\n"
                        + "event: done\ndata: {\"success\":true,\"answer\":\"answer\"}\n\n"));
        PythonSmartBIClient client = newClient(origin, 0, "consumer-secret");

        assertThatThrownBy(() -> client.streamGeneralAnalysis(
                "REST-1", null, null, streamRequest(),
                event -> { throw new IOException("browser disconnected"); }))
                .isInstanceOf(IOException.class)
                .hasMessage("General analysis stream delivery was interrupted")
                .hasMessageNotContaining("browser disconnected");

        Field breakerField = PythonSmartBIClient.class.getDeclaredField("circuitBreaker");
        breakerField.setAccessible(true);
        PythonServiceCircuitBreaker breaker =
                (PythonServiceCircuitBreaker) breakerField.get(client);
        assertThat(breaker.getMetrics()).containsEntry("totalFailures", 0L);
    }

    @Test
    void downstreamConsumerRuntimeFailureDoesNotPoisonPythonAvailabilityOrCircuitBreaker()
            throws Exception {
        MockWebServer origin = startServer();
        String healthyStream = "event: chunk\ndata: \"answer\"\n\n"
                + "event: done\ndata: {\"success\":true,\"answer\":\"answer\"}\n\n";
        origin.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(healthyStream));
        origin.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(healthyStream));
        PythonSmartBIClient client = newClient(origin, 0, "runtime-consumer-secret");
        Field availabilityField = PythonSmartBIClient.class.getDeclaredField("serviceAvailable");
        availabilityField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean availability =
                (java.util.concurrent.atomic.AtomicBoolean) availabilityField.get(client);
        availability.set(true);
        Field breakerField = PythonSmartBIClient.class.getDeclaredField("circuitBreaker");
        breakerField.setAccessible(true);
        PythonServiceCircuitBreaker breaker =
                (PythonServiceCircuitBreaker) breakerField.get(client);
        Field stateField = PythonServiceCircuitBreaker.class.getDeclaredField("state");
        stateField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<PythonServiceCircuitBreaker.CircuitState> state =
                (java.util.concurrent.atomic.AtomicReference<PythonServiceCircuitBreaker.CircuitState>)
                        stateField.get(breaker);
        state.set(PythonServiceCircuitBreaker.CircuitState.HALF_OPEN);

        assertThatThrownBy(() -> client.streamGeneralAnalysis(
                "REST-1", null, null, streamRequest(),
                event -> { throw new IllegalStateException("emitter already complete"); }))
                .isInstanceOf(IOException.class)
                .hasMessage("General analysis stream delivery was interrupted")
                .hasMessageNotContaining("emitter already complete");

        assertThat(breaker.getState())
                .isEqualTo(PythonServiceCircuitBreaker.CircuitState.HALF_OPEN);
        assertThat(breaker.getMetrics())
                .containsEntry("totalRequests", 1L)
                .containsEntry("totalFailures", 0L);
        assertThat(availability).isTrue();

        client.streamGeneralAnalysis(
                "REST-1", null, null, streamRequest(), event -> { });

        assertThat(breaker.getState())
                .isEqualTo(PythonServiceCircuitBreaker.CircuitState.CLOSED);
        assertThat(breaker.getMetrics()).containsEntry("totalFailures", 0L);
        assertThat(breaker.getMetrics()).containsEntry("totalRequests", 2L);
        assertThat(availability).isTrue();
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    @Test
    void dynamicEndpointAllowlistsRejectBeforeNetwork() throws Exception {
        MockWebServer origin = startServer();
        PythonSmartBIClient client = newClient(origin, 0, "allowlist-secret");

        assertThat(client.callFinancialDashboard(
                "/../health", Map.of(), "REST-1")).isNull();
        assertThat(client.callRevenueReport(
                "/api/smartbi/REST-1/revenue-report/generate",
                Map.of(), "REST-1", "restaurant_manager")).isNull();
        assertThat(client.callRevenueReport(
                "/api/smartbi/REST-1/revenue-report/prepare",
                Map.of(), "../REST-1", "restaurant_manager")).isNull();
        assertThat(origin.getRequestCount()).isZero();
    }

    @Test
    void invalidConfiguredBaseIsRejectedAtConstruction() throws Exception {
        MockWebServer origin = startServer();
        for (String invalid : List.of(
                "ftp://localhost:8083",
                "http://user:password@localhost:8083",
                origin.url("/nested").toString(),
                origin.url("/?query=yes").toString(),
                origin.url("/#fragment").toString())) {
            PythonSmartBIConfig config = config(invalid, 0);
            assertThatThrownBy(() -> new PythonSmartBIClient(
                    config, new OkHttpClient(), new ObjectMapper(), breaker(), "secret"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(origin.getRequestCount()).isZero();
    }

    private PythonSmartBIClient newClient(MockWebServer server, int maxRetries, String secret)
            throws Exception {
        return new PythonSmartBIClient(
                config(server.url("/").toString(), maxRetries),
                new OkHttpClient.Builder()
                        .callTimeout(Duration.ofSeconds(5))
                        .build(),
                new ObjectMapper(),
                breaker(),
                secret);
    }

    private static PythonSmartBIClient.GeneralAnalysisCall streamRequest() {
        return new PythonSmartBIClient.GeneralAnalysisCall(
                "analyze", null, "time_range_cost", null, false, 0, false);
    }

    private static PythonSmartBIConfig config(String baseUrl, int maxRetries) {
        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        config.setUrl(baseUrl);
        config.setConnectTimeout(1000);
        config.setTimeout(3000);
        config.setMaxRetries(maxRetries);
        return config;
    }

    private static PythonServiceCircuitBreaker breaker() throws Exception {
        PythonServiceCircuitBreaker breaker = new PythonServiceCircuitBreaker();
        setBreakerField(breaker, "failureThreshold", 100);
        setBreakerField(breaker, "openDurationMs", 100L);
        setBreakerField(breaker, "halfOpenMaxCalls", 2);
        setBreakerField(breaker, "successThresholdInHalfOpen", 2);
        return breaker;
    }

    private static void setBreakerField(
            PythonServiceCircuitBreaker breaker, String name, Object value) throws Exception {
        Field field = PythonServiceCircuitBreaker.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(breaker, value);
    }

    private MockWebServer startServer() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        servers.add(server);
        return server;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
