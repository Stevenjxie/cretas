package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomAdjustControllerGatewayTest {

    private static final String FACTORY_ID = "F006";
    private static final long USER_ID = 42L;

    @Mock
    private ToolExecutionGateway gateway;
    @Mock
    private AuthenticatedToolPrincipalFactory principalFactory;
    @Mock
    private PreviewTokenService previewTokenService;
    @Mock
    private HttpServletRequest request;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BomAdjustController controller;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new BomAdjustController(
                gateway, principalFactory, previewTokenService, objectMapper);
        principal = new ExecutionPrincipal(
                FACTORY_ID,
                "FACTORY",
                Long.toString(USER_ID),
                PrincipalType.USER,
                Set.of("finance_manager"),
                Set.of(),
                Set.of());
    }

    @Test
    void trustedCookieAttributesPreviewThroughGatewayAndIssueBoundProof() {
        Map<String, Object> body = body("把冷冻猪舌用量改成20");
        trustedAttributes(FACTORY_ID, USER_ID, "finance_manager");
        when(gateway.execute(any())).thenReturn(gatewaySuccess(
                ToolExecutionMode.PREVIEW, Map.of("status", "PREVIEW")));
        IntentPreviewToken token = boundToken(body, "proof-bom");
        when(previewTokenService.createBoundToken(any())).thenReturn(token);

        Map<?, ?> data = (Map<?, ?>) controller.preview(FACTORY_ID, body, request).getData();

        assertThat(data.get("confirmationToken")).isEqualTo("proof-bom");
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().mode()).isEqualTo(ToolExecutionMode.PREVIEW);
        assertThat(command.getValue().source()).isEqualTo(ToolExecutionSource.HTTP_CONTROLLER);
        assertThat(command.getValue().principal()).isSameAs(principal);
        ArgumentCaptor<PreviewTokenService.BoundTokenRequest> tokenRequest =
                ArgumentCaptor.forClass(PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokenService).createBoundToken(tokenRequest.capture());
        assertThat(tokenRequest.getValue().parameters()).containsAllEntriesOf(body);
    }

    @Test
    void executeUsesExactProofAndDoesNotTrustHeaderIdentity() {
        Map<String, Object> body = body("把冷冻猪舌用量改成20");
        trustedAttributes(FACTORY_ID, USER_ID, "finance_manager");
        IntentPreviewToken token = boundToken(body, "proof-bom");
        when(previewTokenService.validateTokenForUser("proof-bom", USER_ID))
                .thenReturn(Optional.of(token));
        when(gateway.execute(any())).thenReturn(gatewaySuccess(
                ToolExecutionMode.EXECUTE, Map.of("status", "DONE")));

        Map<?, ?> data = (Map<?, ?>) controller.apply(
                FACTORY_ID, "proof-bom", body, request).getData();

        assertThat(data.get("status")).isEqualTo("DONE");
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().confirmationProof()).get().satisfies(proof -> {
            assertThat(proof.proofToken()).isEqualTo("proof-bom");
            assertThat(proof.commandDigest()).isEqualTo(token.getCommandDigest());
        });
        assertThat(command.getValue().idempotencyKey()).contains(
                "fixed-http-" + ToolCommandDigest.persistentSecretFingerprint("proof-bom"));
    }

    @Test
    void crossUserAndParameterDriftFailClosedBeforeGateway() {
        Map<String, Object> body = body("把冷冻猪舌用量改成20");
        trustedAttributes(FACTORY_ID, USER_ID, "finance_manager");
        IntentPreviewToken crossUser = boundToken(body, "proof-bom");
        crossUser.setUserId(99L);
        when(previewTokenService.validateTokenForUser("proof-bom", USER_ID))
                .thenReturn(Optional.of(crossUser), Optional.of(boundToken(body, "proof-bom")));

        assertThatThrownBy(() -> controller.apply(
                FACTORY_ID, "proof-bom", body, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(409);
        assertThatThrownBy(() -> controller.apply(
                FACTORY_ID,
                "proof-bom",
                body("把冷冻猪舌用量改成30"),
                request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(409);
        verify(gateway, never()).execute(any());
    }

    @Test
    void pathFactoryMismatchFailsClosedWithoutAuthorizationHeader() {
        trustedAttributes("F007", USER_ID, "finance_manager");

        assertThatThrownBy(() -> controller.preview(
                FACTORY_ID, body("把冷冻猪舌用量改成20"), request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(403);
        verify(principalFactory, never()).create(any(), any(), any());
        verify(gateway, never()).execute(any());
    }

    private void trustedAttributes(String factoryId, long userId, String role) {
        lenient().when(request.getAttribute("factoryId")).thenReturn(factoryId);
        lenient().when(request.getAttribute("userId")).thenReturn(userId);
        lenient().when(request.getAttribute("role")).thenReturn(role);
        lenient().when(request.getAttribute("username")).thenReturn("trusted-user");
        lenient().when(principalFactory.create(eq(factoryId), eq(userId), eq(role)))
                .thenReturn(principal);
    }

    private Map<String, Object> body(String instruction) {
        return Map.of("productTypeId", "PT-1", "instruction", instruction);
    }

    private IntentPreviewToken boundToken(Map<String, Object> body, String proofToken) {
        String digest = ToolCommandDigest.commandDigest(
                FACTORY_ID,
                USER_ID,
                "bom_adjust",
                "1.0.0",
                ToolExecutionMode.EXECUTE,
                objectMapper.valueToTree(body));
        return IntentPreviewToken.builder()
                .token(proofToken)
                .factoryId(FACTORY_ID)
                .tenantId(FACTORY_ID)
                .userId(USER_ID)
                .toolName("bom_adjust")
                .descriptorVersion("1.0.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash("b".repeat(64))
                .commandDigest(digest)
                .status(IntentPreviewToken.TokenStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    private ToolExecutionResult gatewaySuccess(
            ToolExecutionMode mode,
            Map<String, Object> data) {
        return new ToolExecutionResult(
                "request-" + mode,
                "bom_adjust",
                "1.0.0",
                "audit-" + mode,
                "trace-" + mode,
                ToolExecutionStatus.SUCCEEDED,
                objectMapper.valueToTree(Map.of("success", true, "data", data)),
                "Tool succeeded",
                false);
    }
}
