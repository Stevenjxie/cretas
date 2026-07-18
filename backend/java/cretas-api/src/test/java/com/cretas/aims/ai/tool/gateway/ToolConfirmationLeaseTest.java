package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolConfirmationLeaseTest {

    @Mock PreviewTokenService previewTokenService;

    ObjectMapper objectMapper;
    ToolConfirmationLease leaseService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        leaseService = new ToolConfirmationLease(previewTokenService, objectMapper);
    }

    @Test
    void returnsOnlyPersistedAndRevalidatedParameters() {
        JsonNode commandParameters = JsonNodeFactory.instance.objectNode().put("userId", 7);
        String digest = ToolCommandDigest.commandDigest(
                "F-1", 42L, "user_disable", "2.0.0",
                ToolExecutionMode.EXECUTE, commandParameters);
        ToolExecutionCommand command = command(commandParameters, digest);
        IntentPreviewToken token = boundToken(digest, commandParameters);
        when(previewTokenService.claimToken("opaque-token", "F-1", 42L, digest))
                .thenReturn(PreviewTokenService.ClaimResult.success(
                        token, "claim-1", Map.of("userId", 7)));

        ToolConfirmationLease.Lease lease = leaseService.claim(
                command, command.principal(), digest).orElseThrow();

        assertThat(lease.persistedParameters().path("userId").asInt()).isEqualTo(7);
        assertThat(lease.toString()).doesNotContain("opaque-token").doesNotContain("userId");
    }

    @Test
    void rejectsPostClaimBindingDriftAndResolvesTheLeaseFailed() {
        JsonNode commandParameters = JsonNodeFactory.instance.objectNode().put("userId", 7);
        String digest = ToolCommandDigest.commandDigest(
                "F-1", 42L, "user_disable", "2.0.0",
                ToolExecutionMode.EXECUTE, commandParameters);
        ToolExecutionCommand command = command(commandParameters, digest);
        IntentPreviewToken drifted = boundToken(digest, commandParameters);
        drifted.setDescriptorVersion("9.9.9");
        when(previewTokenService.claimToken("opaque-token", "F-1", 42L, digest))
                .thenReturn(PreviewTokenService.ClaimResult.success(
                        drifted, "claim-1", Map.of("userId", 7)));

        assertThat(leaseService.claim(command, command.principal(), digest)).isEmpty();
        verify(previewTokenService).resolveClaim(
                "opaque-token", "claim-1", false, "gateway binding rejected");
    }

    @Test
    void proofDigestMismatchDoesNotConsumeToken() {
        JsonNode parameters = JsonNodeFactory.instance.objectNode().put("userId", 7);
        ToolExecutionCommand command = command(parameters, "wrong-digest");

        assertThat(leaseService.claim(command, command.principal(), "expected-digest")).isEmpty();
        verify(previewTokenService, org.mockito.Mockito.never())
                .claimToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    private static ToolExecutionCommand command(JsonNode parameters, String proofDigest) {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F-1", "FACTORY", "42", PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of("hr:read_write"), Set.of());
        return new ToolExecutionCommand(
                "request-1", "correlation-1", "trace-1", "user_disable", "2.0.0",
                parameters, principal, ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.of("idem-1"), Optional.of(new ConfirmationProof(
                "opaque-token", proofDigest, Instant.now().plusSeconds(60))),
                Optional.empty(), Instant.now().plusSeconds(30));
    }

    private IntentPreviewToken boundToken(String digest, JsonNode parameters) {
        return IntentPreviewToken.builder()
                .token("opaque-token")
                .factoryId("F-1")
                .tenantId("F-1")
                .userId(42L)
                .intentCode("USER_DISABLE")
                .toolName("user_disable")
                .descriptorVersion("2.0.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash(ToolCommandDigest.parametersHash(parameters))
                .commandDigest(digest)
                .previewData(parameters.toString())
                .status(IntentPreviewToken.TokenStatus.EXECUTING)
                .claimId("claim-1")
                .claimedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(1))
                .build();
    }
}
