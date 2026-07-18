package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreviewTokenServiceImplTest {

    private IntentPreviewTokenRepository repository;
    private PreviewTokenServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = mock(IntentPreviewTokenRepository.class);
        service = new PreviewTokenServiceImpl(repository, objectMapper);
    }

    @Test
    void boundIssuanceCapturesImmutableExecutionCommand() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IntentPreviewToken saved = service.createBoundToken(boundRequest(ToolExecutionMode.EXECUTE));

        assertThat(saved.getFactoryId()).isEqualTo("F001");
        assertThat(saved.getTenantId()).isEqualTo("F001");
        assertThat(saved.getUserId()).isEqualTo(9L);
        assertThat(saved.getIntentCode()).isEqualTo("ORDER_CREATE");
        assertThat(saved.getToolName()).isEqualTo("order_create");
        assertThat(saved.getDescriptorVersion()).isEqualTo("1.4.0");
        assertThat(saved.getExecutionMode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(saved.getParametersHash()).hasSize(64);
        assertThat(saved.getCommandDigest()).hasSize(64);
        assertThat(saved.getPreviewData()).contains("1.00");
    }

    @Test
    void issuanceRejectsPreviewModeAndLegacyIssuanceCannotBeClaimed() {
        assertThatThrownBy(() -> service.createBoundToken(boundRequest(ToolExecutionMode.PREVIEW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTE");

        IntentPreviewToken legacy = IntentPreviewToken.builder()
                .token("legacy")
                .factoryId("F001")
                .userId(9L)
                .intentCode("ORDER_CREATE")
                .previewData("{\"amount\":1}")
                .status(TokenStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(repository.findByToken("legacy")).thenReturn(Optional.of(legacy));

        assertThat(service.claimToken("legacy", "F001", 9L).isSuccess()).isFalse();
        verify(repository, never()).claimForExecution(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void claimRejectsCrossFactoryCrossUserProofMismatchExpiryAndProcessedStatus() throws Exception {
        IntentPreviewToken bound = boundToken("bound", LocalDateTime.now().plusMinutes(5));
        when(repository.findByToken("bound")).thenReturn(Optional.of(bound));

        assertThat(service.claimToken("bound", "OTHER", 9L).getMessage()).contains("不匹配");
        assertThat(service.claimToken("bound", "F001", 10L).getMessage()).contains("不匹配");
        assertThat(service.claimToken("bound", "F001", 9L, "wrong-digest").getMessage())
                .contains("确认凭据");

        bound.setStatus(TokenStatus.CONFIRMED);
        assertThat(service.claimToken("bound", "F001", 9L).getMessage()).contains("已被处理");

        IntentPreviewToken expired = boundToken("expired", LocalDateTime.now().minusSeconds(1));
        when(repository.findByToken("expired")).thenReturn(Optional.of(expired));
        assertThat(service.claimToken("expired", "F001", 9L).getMessage()).contains("已过期");
        verify(repository).expirePendingToken(
                eq("expired"), eq("F001"), eq(9L), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.EXPIRED));
    }

    @Test
    void claimUsesPersistedJsonForDigestAndReturnsExactlyOneLease() throws Exception {
        IntentPreviewToken bound = boundToken("bound-scale", LocalDateTime.now().plusMinutes(5));
        when(repository.findByToken("bound-scale")).thenReturn(Optional.of(bound));
        when(repository.claimForExecution(
                eq("bound-scale"), eq("F001"), eq("F001"), eq(9L),
                eq(bound.getCommandDigest()), eq(bound.getParametersHash()), eq("order_create"),
                eq("1.4.0"), eq(ToolExecutionMode.EXECUTE), any(), any(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.EXECUTING))).thenReturn(1);
        when(repository.findByTokenAndClaimIdAndStatus(
                eq("bound-scale"), any(), eq(TokenStatus.EXECUTING)))
                .thenAnswer(invocation -> {
                    bound.setStatus(TokenStatus.EXECUTING);
                    bound.setClaimId(invocation.getArgument(1));
                    return Optional.of(bound);
                });

        ClaimResult result = service.claimToken(
                "bound-scale", "F001", 9L, bound.getCommandDigest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getClaimId()).isNotBlank();
        assertThat(result.getParameters()).containsEntry("label", "x");
    }

    @Test
    void resolutionWritesTerminalSuccessOrFailureOnlyThroughLeaseOwner() {
        when(repository.resolveClaim(
                eq("t"), eq("c-success"), eq(TokenStatus.EXECUTING),
                eq(TokenStatus.CONFIRMED), any(), eq("done"))).thenReturn(1);
        when(repository.resolveClaim(
                eq("t"), eq("c-fail"), eq(TokenStatus.EXECUTING),
                eq(TokenStatus.FAILED), any(), eq("tool failed"))).thenReturn(1);

        assertThat(service.resolveClaim("t", "c-success", true, "done")).isTrue();
        assertThat(service.resolveClaim("t", "c-fail", false, "tool failed")).isTrue();
    }

    private BoundTokenRequest boundRequest(ToolExecutionMode mode) {
        return new BoundTokenRequest(
                "F001", 9L, "tester", "ORDER_CREATE", "Create order",
                "order_create", "1.4.0", mode, "ORDER", "O1", "CREATE",
                Map.of("amount", new java.math.BigDecimal("1.00"), "label", "x"),
                Map.of(), Map.of(), 300);
    }

    private IntentPreviewToken boundToken(String token, LocalDateTime expiresAt) throws Exception {
        JsonNode parameters = objectMapper.readTree("{\"amount\":1.00,\"label\":\"x\"}");
        return IntentPreviewToken.builder()
                .token(token)
                .factoryId("F001")
                .tenantId("F001")
                .userId(9L)
                .intentCode("ORDER_CREATE")
                .toolName("order_create")
                .descriptorVersion("1.4.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash(ToolCommandDigest.parametersHash(parameters))
                .commandDigest(ToolCommandDigest.commandDigest(
                        "F001", 9L, "order_create", "1.4.0",
                        ToolExecutionMode.EXECUTE, parameters))
                .previewData(objectMapper.writeValueAsString(parameters))
                .status(TokenStatus.PENDING)
                .expiresAt(expiresAt)
                .build();
    }
}
