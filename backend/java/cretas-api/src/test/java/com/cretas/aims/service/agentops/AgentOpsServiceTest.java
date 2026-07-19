package com.cretas.aims.service.agentops;

import com.cretas.aims.client.agentops.AgentOpsClient;
import com.cretas.aims.dto.agentops.AgentOpsRerunExperimentRequest;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOpsServiceTest {
    private AgentOpsClient client;
    private IntentConfigManagementService domains;
    private AgentOpsService service;

    @BeforeEach
    void setUp() {
        client = mock(AgentOpsClient.class);
        domains = mock(IntentConfigManagementService.class);
        service = new AgentOpsService(client, domains);
        when(client.isConfigured()).thenReturn(true);
        when(domains.resolveBusinessDomain("R001")).thenReturn("RESTAURANT");
    }

    @Test
    void trustedContextIsNormalizedAndForwarded() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(client.getTrace(any(), anyLong(), anyInt(), any())).thenReturn(new ObjectMapper().readTree("{}"));
        service.trace("R001", "42", "PLATFORM_ADMIN", "corr-1", runId, 10, 20);
        verify(client).getTrace(runId, 10, 20,
                new AgentOpsClient.TrustedContext("R001", "42", "platform_admin", "corr-1"));
    }

    @Test
    void nonAdminAndNonRestaurantFailBeforeNetwork() {
        assertThatThrownBy(() -> service.listEvalSets("R001", "42", "operator", "corr"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
        when(domains.resolveBusinessDomain("R001")).thenReturn("FACTORY");
        assertThatThrownBy(() -> service.listEvalSets("R001", "42", "platform_admin", "corr"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void unknownUpstreamConflictRemainsSafeGenericConflict() throws Exception {
        when(client.listEvalSets(any())).thenThrow(new AgentOpsClient.UpstreamException(409));
        assertThatThrownBy(() -> service.listEvalSets("R001", "42", "platform_admin", "corr"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode().value()).isEqualTo(409);
                    assertThat(status.getReason()).isEqualTo("AGENT_OPS_CONFLICT");
                });
    }

    @Test
    void allowlistedConflictCodesRemainExact409Reasons() throws Exception {
        when(client.listEvalSets(any()))
                .thenThrow(new AgentOpsClient.UpstreamException(409, "EVAL_SET_VERSION_EXISTS"))
                .thenThrow(new AgentOpsClient.UpstreamException(409, "IDEMPOTENCY_KEY_REUSED"))
                .thenThrow(new AgentOpsClient.UpstreamException(409, "EVALUATOR_BUILD_UNAVAILABLE"));

        for (String code : new String[]{"EVAL_SET_VERSION_EXISTS", "IDEMPOTENCY_KEY_REUSED",
                "EVALUATOR_BUILD_UNAVAILABLE"}) {
            assertThatThrownBy(() -> service.listEvalSets("R001", "42", "platform_admin", "corr"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, status -> {
                        assertThat(status.getStatusCode().value()).isEqualTo(409);
                        assertThat(status.getReason()).isEqualTo(code);
                    });
        }
    }

    @Test
    void rerunForwardsIdempotencyBodyAndReturnsOperationMetadata() throws Exception {
        UUID experimentId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        AgentOpsRerunExperimentRequest body = new AgentOpsRerunExperimentRequest();
        body.setSchemaVersion("1.0");
        body.setRequestId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        when(client.rerunExperiment(experimentId, body, new AgentOpsClient.TrustedContext(
                "R001", "42", "platform_admin", "corr-1")))
                .thenReturn(new ObjectMapper().readTree(
                        "{\"operationKind\":\"RERUN\",\"sourceExperimentId\":\"" + experimentId + "\"}"));

        var result = service.rerun("R001", "42", "PLATFORM_ADMIN", "corr-1", experimentId, body);

        assertThat(result.path("operationKind").asText()).isEqualTo("RERUN");
        assertThat(result.path("sourceExperimentId").asText()).isEqualTo(experimentId.toString());
        verify(client).rerunExperiment(experimentId, body, new AgentOpsClient.TrustedContext(
                "R001", "42", "platform_admin", "corr-1"));
    }


    @Test
    void upstream503IsPreservedAndOtherUnexpectedErrorsRemain502() throws Exception {
        when(client.listEvalSets(any()))
                .thenThrow(new AgentOpsClient.UpstreamException(503))
                .thenThrow(new AgentOpsClient.UpstreamException(404))
                .thenThrow(new AgentOpsClient.UpstreamException(422))
                .thenThrow(new AgentOpsClient.UpstreamException(500));
        assertThatThrownBy(() -> service.listEvalSets("R001", "42", "platform_admin", "corr"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode().value()).isEqualTo(503);
                    assertThat(status.getReason()).isEqualTo("AGENT_OPS_STORE_UNAVAILABLE");
                });
        for (int upstream : new int[]{404, 422, 500}) {
            assertThatThrownBy(() -> service.listEvalSets("R001", "42", "platform_admin", "corr"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, status -> {
                        assertThat(status.getStatusCode().value()).as("upstream %s", upstream).isEqualTo(502);
                        assertThat(status.getReason()).isEqualTo("AGENT_OPS_BAD_UPSTREAM");
                    });
        }
    }
}
