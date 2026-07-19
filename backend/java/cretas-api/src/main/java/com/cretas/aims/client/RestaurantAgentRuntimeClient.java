package com.cretas.aims.client;

import com.cretas.aims.dto.restaurantagent.RestaurantAgentEventV1;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunCancelResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Exact-path Java-to-Python client for the bounded restaurant runtime. */
@Component
public class RestaurantAgentRuntimeClient {

    public static final String INTERNAL_RUNS_PATH = "/api/internal/smartbi/agent/runs";
    public static final String EVENT_NAME = "agent.event.v1";
    private static final Set<String> RUN_STATES = Set.of(
            "RUNNING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "BUDGET_EXCEEDED");
    private static final Set<String> EVENT_TYPES = Set.of(
            "RUN_STARTED", "ROUTE_SELECTED", "PLAN_CREATED", "STEP_STARTED",
            "STEP_COMPLETED", "STEP_FAILED", "EVIDENCE_RECORDED", "EVIDENCE_GAP",
            "REPLAN", "CLARIFICATION", "CANCEL_REQUESTED", "BUDGET_EXCEEDED",
            "RUN_CANCELLED", "RUN_COMPLETED", "RUN_FAILED");
    private static final Set<String> CANCEL_RESULTS = Set.of(
            "REQUESTED", "ALREADY_REQUESTED", "ALREADY_TERMINAL");
    private static final MediaType JSON = Objects.requireNonNull(
            MediaType.parse("application/json; charset=utf-8"));

    private final OkHttpClient requestClient;
    private final OkHttpClient streamClient;
    private final ObjectMapper objectMapper;
    private final HttpUrl runsUrl;
    private final String internalSecret;

    public RestaurantAgentRuntimeClient(
            @Qualifier("aiServiceHttpClient") OkHttpClient sharedClient,
            @Qualifier("pythonAiBaseUrl") String pythonBaseUrl,
            @Qualifier("pythonAiInternalSecret") String internalSecret,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
        this.runsUrl = exactRunsUrl(pythonBaseUrl);
        this.requestClient = sharedClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
        this.streamClient = requestClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean isConfigured() {
        return !internalSecret.isBlank();
    }

    public UpstreamStream openStartStream(
            RestaurantAgentRunStartRequest body,
            TrustedContext context) throws IOException {
        requireConfigured();
        Request request = withTrustedHeaders(new Request.Builder()
                        .url(runsUrl)
                        .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                        .header("Accept", "text/event-stream"),
                context)
                .build();
        Call call = streamClient.newCall(request);
        Response response = call.execute();
        if (!response.isSuccessful()) {
            int status = response.code();
            response.close();
            throw new UpstreamHttpException(status);
        }
        ResponseBody responseBody = response.body();
        String contentType = response.header("Content-Type", "");
        if (responseBody == null || !contentType.toLowerCase().startsWith("text/event-stream")) {
            response.close();
            throw new IOException("Restaurant agent runtime returned a non-SSE response");
        }
        final UUID runId;
        try {
            runId = requireCanonicalRunId(response.header("X-Agent-Run-Id"));
        } catch (IOException ex) {
            response.close();
            throw ex;
        }
        return new UpstreamStream(call, response, runId);
    }

    public RestaurantAgentRunReplayResponse replay(
            UUID runId,
            long afterSequence,
            TrustedContext context) throws IOException {
        requireConfigured();
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        HttpUrl url = runsUrl.newBuilder()
                .addPathSegment(runId.toString())
                .addPathSegment("events")
                .addQueryParameter("afterSequence", Long.toString(afterSequence))
                .build();
        Request request = withTrustedHeaders(new Request.Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json"),
                context)
                .build();
        try (Response response = requestClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new UpstreamHttpException(response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Restaurant agent runtime returned an empty replay");
            }
            RestaurantAgentRunReplayResponse replay = objectMapper.readValue(
                    responseBody.byteStream(), RestaurantAgentRunReplayResponse.class);
            validateReplay(runId, afterSequence, replay);
            return replay;
        }
    }

    public RestaurantAgentRunCancelResponse cancel(
            UUID runId,
            TrustedContext context) throws IOException {
        requireConfigured();
        HttpUrl url = runsUrl.newBuilder()
                .addPathSegment(runId.toString())
                .addPathSegment("cancel")
                .build();
        Request request = withTrustedHeaders(new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(new byte[0], JSON))
                        .header("Accept", "application/json"),
                context)
                .build();
        try (Response response = requestClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new UpstreamHttpException(response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Restaurant agent runtime returned an empty cancel response");
            }
            RestaurantAgentRunCancelResponse cancellation = objectMapper.readValue(
                    responseBody.byteStream(), RestaurantAgentRunCancelResponse.class);
            validateCancellation(runId, cancellation);
            return cancellation;
        }
    }

    public void validateEventFrame(UUID expectedRunId, String expectedId, String rawData) throws IOException {
        RestaurantAgentEventV1 event = objectMapper.readValue(rawData, RestaurantAgentEventV1.class);
        validateEvent(expectedRunId, event);
        if (!Long.toString(event.getSequence()).equals(expectedId)) {
            throw new IOException("Invalid restaurant agent Event v1 frame");
        }
    }

    private Request.Builder withTrustedHeaders(Request.Builder builder, TrustedContext context) {
        return builder
                .header("X-Internal-Secret", internalSecret)
                .header("X-Factory-Id", context.factoryId())
                .header("X-User-Id", context.userId())
                .header("X-User-Role", context.role())
                .header("X-Business-Type", context.businessType())
                .header("X-Correlation-ID", context.correlationId());
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Restaurant agent runtime internal secret is not configured");
        }
    }

    private void validateReplay(
            UUID requestedRunId,
            long afterSequence,
            RestaurantAgentRunReplayResponse replay) throws IOException {
        if (replay == null
                || !RestaurantAgentRunStartRequest.SCHEMA_VERSION.equals(replay.getSchemaVersion())
                || !requestedRunId.toString().equals(replay.getRunId())
                || !RestaurantAgentRunStartRequest.ROUTE_CODE.equals(replay.getRouteCode())
                || !RUN_STATES.contains(replay.getState())
                || replay.getNextEventSequence() < 0
                || replay.getEvents() == null) {
            throw new IOException("Invalid restaurant agent replay contract");
        }
        long previous = afterSequence;
        long lastReturnedSequence = -1;
        List<RestaurantAgentEventV1> events = replay.getEvents();
        for (RestaurantAgentEventV1 event : events) {
            validateEvent(requestedRunId, event);
            if (event.getSequence() <= previous) {
                throw new IOException("Invalid restaurant agent replay event ordering");
            }
            previous = event.getSequence();
            lastReturnedSequence = event.getSequence();
        }
        if (lastReturnedSequence >= 0 && replay.getNextEventSequence() < lastReturnedSequence) {
            throw new IOException("Invalid restaurant agent replay cursor");
        }
    }

    private void validateCancellation(
            UUID requestedRunId,
            RestaurantAgentRunCancelResponse cancellation) throws IOException {
        if (cancellation == null
                || !RestaurantAgentRunStartRequest.SCHEMA_VERSION.equals(cancellation.getSchemaVersion())
                || !requestedRunId.toString().equals(cancellation.getRunId())
                || !CANCEL_RESULTS.contains(cancellation.getResult())
                || !RUN_STATES.contains(cancellation.getState())
                || cancellation.getNextEventSequence() < 0) {
            throw new IOException("Invalid restaurant agent cancellation contract");
        }
    }

    private void validateEvent(UUID expectedRunId, RestaurantAgentEventV1 event) throws IOException {
        if (event == null
                || !RestaurantAgentRunStartRequest.SCHEMA_VERSION.equals(event.getSchemaVersion())
                || !expectedRunId.toString().equals(event.getRunId())
                || event.getSequence() <= 0
                || !EVENT_TYPES.contains(event.getEventType())
                || event.getPayload() == null) {
            throw new IOException("Invalid restaurant agent Event v1 contract");
        }
    }

    private UUID requireCanonicalRunId(String rawRunId) throws IOException {
        if (rawRunId == null) {
            throw new IOException("Restaurant agent runtime omitted X-Agent-Run-Id");
        }
        try {
            UUID parsed = UUID.fromString(rawRunId);
            if (!parsed.toString().equals(rawRunId)) {
                throw new IOException("Restaurant agent runtime returned a non-canonical run id");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Restaurant agent runtime returned an invalid run id", ex);
        }
    }

    private static HttpUrl exactRunsUrl(String pythonBaseUrl) {
        HttpUrl base;
        try {
            base = HttpUrl.get(pythonBaseUrl);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid Python base URL", ex);
        }
        if (!("http".equals(base.scheme()) || "https".equals(base.scheme()))
                || !base.username().isEmpty()
                || !base.password().isEmpty()
                || !base.encodedPath().equals("/")
                || base.query() != null
                || base.fragment() != null) {
            throw new IllegalArgumentException("Python base URL must contain only scheme, host and port");
        }
        return base.newBuilder().encodedPath(INTERNAL_RUNS_PATH).build();
    }

    public record TrustedContext(
            String factoryId,
            String userId,
            String role,
            String businessType,
            String correlationId) {
    }

    public static class UpstreamHttpException extends IOException {
        private final int statusCode;

        public UpstreamHttpException(int statusCode) {
            super("Restaurant agent runtime returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /** Owns the upstream response until the downstream emitter terminates. */
    public static class UpstreamStream implements AutoCloseable {
        private final Call call;
        private final Response response;
        private final UUID runId;

        public UpstreamStream(Call call, Response response, UUID runId) {
            this.call = call;
            this.response = response;
            this.runId = runId;
        }

        public BufferedSource source() {
            return Objects.requireNonNull(response.body()).source();
        }

        public UUID runId() {
            return runId;
        }

        public void cancel() {
            call.cancel();
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
