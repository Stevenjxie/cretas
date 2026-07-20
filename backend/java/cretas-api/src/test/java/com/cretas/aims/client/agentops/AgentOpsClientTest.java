package com.cretas.aims.client.agentops;

import com.cretas.aims.dto.agentops.AgentOpsCreateEvalSetRequest;
import com.cretas.aims.dto.agentops.AgentOpsImportRuntimeCorpusRequest;
import com.cretas.aims.dto.agentops.AgentOpsRerunExperimentRequest;
import com.cretas.aims.dto.agentops.AgentOpsRunRuntimeShadowRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOpsClientTest {
    private MockWebServer server;
    private ObjectMapper mapper;
    private AgentOpsClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        mapper = new ObjectMapper().findAndRegisterModules();
        client = new AgentOpsClient(new OkHttpClient(), server.url("/").toString(), "secret", mapper);
    }

    @AfterEach
    void close() throws Exception { server.shutdown(); }

    @Test
    void createUsesExactPathAndSignedTrustedHeadersWithoutBodyIdentity() throws Exception {
        server.enqueue(json(201, "{\"evalSetId\":\"00000000-0000-0000-0000-000000000001\"}"));
        JsonNode response = client.createEvalSet(body(), context());
        assertThat(response.path("evalSetId").asText()).isNotBlank();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo("/api/internal/smartbi/agent/runs/ops/eval-sets");
        assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("secret");
        assertThat(request.getHeader("X-Factory-Id")).isEqualTo("R001");
        assertThat(request.getHeader("X-User-Id")).isEqualTo("42");
        assertThat(request.getHeader("X-User-Role")).isEqualTo("platform_admin");
        assertThat(request.getHeader("X-Business-Type")).isEqualTo("RESTAURANT");
        JsonNode outbound = mapper.readTree(request.getBody().readUtf8());
        assertThat(outbound.has("factoryId")).isFalse();
        assertThat(outbound.has("userId")).isFalse();
        assertThat(outbound.has("tenantId")).isFalse();
        assertThat(outbound.path("requestId").asText()).isEqualTo(body().getRequestId().toString());
    }

    @Test
    void runtimeCorpusAndShadowUseTrustedHeadersAndNeverSendIdentityOrActualSnapshots() throws Exception {
        server.enqueue(json(201, "{\"evalSetId\":\"00000000-0000-4000-8000-000000000001\"}"));
        AgentOpsImportRuntimeCorpusRequest importBody = new AgentOpsImportRuntimeCorpusRequest();
        importBody.setSchemaVersion("1.0");
        importBody.setRequestId(UUID.fromString("00000000-0000-4000-8000-000000000091"));
        importBody.setName("runtime corpus");
        importBody.setVersion(1);
        importBody.setMaxCases(20);
        client.importRuntimeCorpus(importBody, context());

        RecordedRequest imported = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(imported.getPath()).isEqualTo(
                "/api/internal/smartbi/agent/runs/ops/eval-sets/import-runtime-corpus");
        assertThat(imported.getHeader("X-Factory-Id")).isEqualTo("R001");
        assertThat(imported.getHeader("X-User-Id")).isEqualTo("42");
        assertThat(imported.getHeader("X-User-Role")).isEqualTo("platform_admin");
        assertThat(imported.getHeader("X-Correlation-ID")).isEqualTo("corr-001");
        JsonNode importJson = mapper.readTree(imported.getBody().readUtf8());
        assertThat(importJson.has("factoryId")).isFalse();
        assertThat(importJson.has("userId")).isFalse();
        assertThat(importJson.has("cases")).isFalse();

        server.enqueue(json(201, "{\"operationKind\":\"RUNTIME_SHADOW\"}"));
        AgentOpsRunRuntimeShadowRequest shadowBody = new AgentOpsRunRuntimeShadowRequest();
        shadowBody.setSchemaVersion("1.0");
        shadowBody.setRequestId(UUID.fromString("00000000-0000-4000-8000-000000000092"));
        shadowBody.setEvalSetId(UUID.fromString("00000000-0000-4000-8000-000000000001"));
        shadowBody.setConfigSnapshot(Map.of(
                "promptSnapshotDigest", "1".repeat(64),
                "modelSnapshotDigest", "2".repeat(64),
                "toolSnapshotDigest", "3".repeat(64)));
        client.runRuntimeShadow(shadowBody, context());

        RecordedRequest shadow = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(shadow.getPath()).isEqualTo(
                "/api/internal/smartbi/agent/runs/ops/experiments/runtime-shadow");
        JsonNode shadowJson = mapper.readTree(shadow.getBody().readUtf8());
        assertThat(shadowJson.has("actualSnapshots")).isFalse();
        assertThat(shadowJson.has("factoryId")).isFalse();
        assertThat(shadowJson.has("userId")).isFalse();
    }

    @Test
    void rerunUsesRequiredIdempotencyBodyAndPreservesSafeOperationMetadata() throws Exception {
        UUID experimentId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        server.enqueue(json(201, "{\"operationKind\":\"RERUN\",\"sourceExperimentId\":\"" + sourceId + "\"}"));
        AgentOpsRerunExperimentRequest body = new AgentOpsRerunExperimentRequest();
        body.setSchemaVersion("1.0");
        body.setRequestId(requestId);

        JsonNode response = client.rerunExperiment(experimentId, body, context());

        assertThat(response.path("operationKind").asText()).isEqualTo("RERUN");
        assertThat(response.path("sourceExperimentId").asText()).isEqualTo(sourceId.toString());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo(
                "/api/internal/smartbi/agent/runs/ops/experiments/" + experimentId + "/rerun");
        assertThat(mapper.readTree(request.getBody().readUtf8())).isEqualTo(mapper.readTree(
                "{\"schemaVersion\":\"1.0\",\"requestId\":\"" + requestId + "\"}"));
    }

    @Test
    void conflictDetailIsAllowlistedAndPreservedWithoutLeakingArbitraryUpstreamText() {
        server.enqueue(json(409, "{\"detail\":\"IDEMPOTENCY_KEY_REUSED\",\"token\":\"secret\"}"));
        assertThatThrownBy(() -> client.listEvalSets(context()))
                .isInstanceOfSatisfying(AgentOpsClient.UpstreamException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(409);
                    assertThat(ex.getDetailCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                    assertThat(ex.getMessage()).doesNotContain("secret");
                });

        server.enqueue(json(409, "{\"detail\":\"UPSTREAM_PRIVATE_TEXT\"}"));
        assertThatThrownBy(() -> client.listEvalSets(context()))
                .isInstanceOfSatisfying(AgentOpsClient.UpstreamException.class,
                        ex -> assertThat(ex.getDetailCode()).isNull());
    }

    @Test
    void emptyJsonConflictReturnsSafe409WithoutNullPointerException() {
        server.enqueue(json(409, "{}"));

        assertThatThrownBy(() -> client.listEvalSets(context()))
                .isInstanceOfSatisfying(AgentOpsClient.UpstreamException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(409);
                    assertThat(ex.getDetailCode()).isNull();
                });
    }

    @Test
    void traceAndCompareUseEncodedPathAndBoundedQuery() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID baseline = UUID.fromString("00000000-0000-0000-0000-000000000002");
        server.enqueue(json(200, "{}"));
        client.compareExperiments(id, baseline, context());
        assertThat(server.takeRequest().getPath()).isEqualTo(
                "/api/internal/smartbi/agent/runs/ops/experiments/" + id + "/compare?baselineId=" + baseline);
        assertThatThrownBy(() -> client.getTrace(id, 0, 101, context()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectIsNotFollowedAndBlankSecretFailsBeforeNetwork() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")));
        assertThatThrownBy(() -> client.listEvalSets(context()))
                .isInstanceOf(AgentOpsClient.UpstreamException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
        AgentOpsClient blank = new AgentOpsClient(new OkHttpClient(), server.url("/").toString(), " ", mapper);
        assertThatThrownBy(() -> blank.listEvalSets(context())).isInstanceOf(IllegalStateException.class);
        assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void oversizedResponseIsRejectedWithoutParsing() {
        server.enqueue(json(200, "{\"data\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}"));
        assertThatThrownBy(() -> client.listEvalSets(context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void oversizedRequestIsRejectedBeforeNetwork() {
        AgentOpsCreateEvalSetRequest request = body();
        request.setDescription("x".repeat(4 * 1024 * 1024));
        assertThatThrownBy(() -> client.createEvalSet(request, context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("request too large");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void sensitiveResponseFieldsAreRemovedButSnapshotDigestsRemain() throws Exception {
        server.enqueue(json(200, """
                {"raw_question":"secret question","promptSnapshotDigest":"abc",
                 "nested":{"token":"bearer","status":"ok"},
                 "items":[{"apiKey":"hidden","modelSnapshotDigest":"def"}]}
                """));
        JsonNode response = client.listEvalSets(context());
        assertThat(response.has("raw_question")).isFalse();
        assertThat(response.path("promptSnapshotDigest").asText()).isEqualTo("abc");
        assertThat(response.path("nested").has("token")).isFalse();
        assertThat(response.path("nested").path("status").asText()).isEqualTo("ok");
        assertThat(response.path("items").path(0).has("apiKey")).isFalse();
        assertThat(response.path("items").path(0).path("modelSnapshotDigest").asText()).isEqualTo("def");
    }

    private MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    private AgentOpsClient.TrustedContext context() {
        return new AgentOpsClient.TrustedContext("R001", "42", "platform_admin", "corr-001");
    }

    private AgentOpsCreateEvalSetRequest body() {
        AgentOpsCreateEvalSetRequest request = new AgentOpsCreateEvalSetRequest();
        request.setSchemaVersion("1.0");
        request.setRequestId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        request.setName("baseline");
        request.setVersion(1);
        AgentOpsCreateEvalSetRequest.EvalCase one = new AgentOpsCreateEvalSetRequest.EvalCase();
        one.setCaseId("case-1");
        one.setExpectedRoute("GROSS_MARGIN_DECLINE_ATTRIBUTION");
        one.setRequiredTools(List.of("margin"));
        one.setNumericTruthRefs(Map.of("e1:f1", "1"));
        one.setMaxRounds(2);
        one.setMaxToolCalls(2);
        request.setCases(List.of(one));
        return request;
    }
}
