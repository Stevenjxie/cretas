package com.cretas.aims.controller.agentops;

import com.cretas.aims.service.agentops.AgentOpsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentOpsControllerTest {
    private AgentOpsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(AgentOpsService.class);
        when(service.listEvalSets(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ObjectMapper().readTree("{\"items\":[]}"));
        mvc = MockMvcBuilders.standaloneSetup(new AgentOpsController(service)).build();
    }

    @Test
    void listUsesTrustedAttributesAndNoStore() throws Exception {
        mvc.perform(get("/api/mobile/R001/agent-ops/eval-sets")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "PLATFORM_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray());
        verify(service).listEvalSets(eq("R001"), eq("42"), eq("platform_admin"), anyString());
    }

    @Test
    void pathFactoryMismatchAndMissingActorFailBeforeService() throws Exception {
        mvc.perform(get("/api/mobile/R002/agent-ops/eval-sets")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mobile/R001/agent-ops/eval-sets")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("role", "platform_admin"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).listEvalSets(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bodySuppliedTenantIsRejectedLocally() throws Exception {
        String body = """
                {"schemaVersion":"1.0","requestId":"00000000-0000-0000-0000-000000000011",
                 "name":"baseline","version":1,"description":"",
                 "cases":[{"caseId":"c1","expectedRoute":"GROSS_MARGIN_DECLINE_ATTRIBUTION",
                 "requiredTools":[],"numericTruthRefs":{},"maxRounds":2,"maxToolCalls":10}],
                 "factoryId":"ATTACKER"}
                """;
        mvc.perform(post("/api/mobile/R001/agent-ops/eval-sets")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).createEvalSet(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void createAndRunRequireUuidRequestIdBeforeService() throws Exception {
        String create = """
                {"schemaVersion":"1.0","name":"baseline","version":1,"description":"",
                 "cases":[{"caseId":"c1","expectedRoute":"GROSS_MARGIN_DECLINE_ATTRIBUTION",
                 "requiredTools":[],"numericTruthRefs":{},"maxRounds":2,"maxToolCalls":10}]}
                """;
        mvc.perform(post("/api/mobile/R001/agent-ops/eval-sets")
                        .requestAttr("factoryId", "R001").requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isBadRequest());

        String run = """
                {"schemaVersion":"1.0","requestId":"not-a-uuid",
                 "evalSetId":"00000000-0000-0000-0000-000000000020",
                 "configSnapshot":{"modelSnapshotDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                 "actualSnapshots":{"c1":{"routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION",
                 "tools":[],"numericTruthRefs":{},"roundsUsed":1,"toolCallsUsed":1}},
                 "bounds":{"maxCases":1,"maxConcurrency":1,"perCaseTimeoutMs":1000}}
                """;
        mvc.perform(post("/api/mobile/R001/agent-ops/experiments")
                        .requestAttr("factoryId", "R001").requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON).content(run))
                .andExpect(status().isBadRequest());

        verify(service, never()).createEvalSet(anyString(), anyString(), anyString(), anyString(), any());
        verify(service, never()).runExperiment(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void rerunRequiresStrictIdempotencyBodyAndReturnsOperationMetadata() throws Exception {
        UUID experimentId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        when(service.rerun(anyString(), anyString(), anyString(), anyString(), eq(experimentId), any()))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"operationKind\":\"RERUN\",\"sourceExperimentId\":\"" + sourceId + "\"}"));

        mvc.perform(post("/api/mobile/R001/agent-ops/experiments/{id}/rerun", experimentId)
                        .requestAttr("factoryId", "R001").requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":\"1.0\",\"requestId\":\"00000000-0000-0000-0000-000000000011\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationKind").value("RERUN"))
                .andExpect(jsonPath("$.data.sourceExperimentId").value(sourceId.toString()));
        verify(service).rerun(eq("R001"), eq("42"), eq("platform_admin"), anyString(),
                eq(experimentId), any());

        mvc.perform(post("/api/mobile/R001/agent-ops/experiments/{id}/rerun", experimentId)
                        .requestAttr("factoryId", "R001").requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":\"1.0\",\"requestId\":\"00000000-0000-0000-0000-000000000011\",\"factoryId\":\"ATTACKER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runtimeShadowUsesTrustedRequestIdentityAndRejectsClientActualSnapshots() throws Exception {
        when(service.runRuntimeShadow(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"operationKind\":\"RUNTIME_SHADOW\"}"));
        String valid = """
                {"schemaVersion":"1.0",
                 "requestId":"00000000-0000-4000-8000-000000000091",
                 "evalSetId":"00000000-0000-4000-8000-000000000092",
                 "configSnapshot":{
                   "promptSnapshotDigest":"1111111111111111111111111111111111111111111111111111111111111111",
                   "modelSnapshotDigest":"2222222222222222222222222222222222222222222222222222222222222222",
                   "toolSnapshotDigest":"3333333333333333333333333333333333333333333333333333333333333333"},
                 "bounds":{"maxCases":20,"maxConcurrency":2,"perCaseTimeoutMs":75000}}
                """;

        mvc.perform(post("/api/mobile/R001/agent-ops/experiments/runtime-shadow")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "PLATFORM_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationKind").value("RUNTIME_SHADOW"));
        verify(service).runRuntimeShadow(
                eq("R001"), eq("42"), eq("platform_admin"), anyString(), any());

        String withActual = valid.substring(0, valid.lastIndexOf('}'))
                + ",\"actualSnapshots\":{}}";
        mvc.perform(post("/api/mobile/R001/agent-ops/experiments/runtime-shadow")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withActual))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runtimeCorpusImportDerivesIdentityAndRejectsClientCases() throws Exception {
        when(service.importRuntimeCorpus(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ObjectMapper().readTree("{\"caseCount\":2}"));
        String valid = """
                {"schemaVersion":"1.0",
                 "requestId":"00000000-0000-4000-8000-000000000093",
                 "name":"runtime corpus","version":1,"description":"trusted",
                 "maxCases":20}
                """;
        mvc.perform(post("/api/mobile/R001/agent-ops/eval-sets/import-runtime-corpus")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "PLATFORM_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseCount").value(2));
        verify(service).importRuntimeCorpus(
                eq("R001"), eq("42"), eq("platform_admin"), anyString(), any());

        String withCases = valid.substring(0, valid.lastIndexOf('}'))
                + ",\"cases\":[]}";
        mvc.perform(post("/api/mobile/R001/agent-ops/eval-sets/import-runtime-corpus")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withCases))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tracePassesOnlyTrustedPrincipalAndBoundedLimit() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(service.trace(anyString(), anyString(), anyString(), anyString(), eq(id), eq(10L), eq(20)))
                .thenReturn(new ObjectMapper().readTree("{\"runId\":\"" + id + "\"}"));
        mvc.perform(get("/api/mobile/R001/agent-ops/traces/{id}", id)
                        .param("afterSequence", "10").param("limit", "20")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(id.toString()));
        verify(service).trace(eq("R001"), eq("42"), eq("platform_admin"), anyString(), eq(id), eq(10L), eq(20));

        mvc.perform(get("/api/mobile/R001/agent-ops/traces/{id}", id).param("limit", "101")
                        .requestAttr("factoryId", "R001")
                        .requestAttr("userId", 42L)
                        .requestAttr("role", "platform_admin"))
                .andExpect(status().isBadRequest());
    }
}
