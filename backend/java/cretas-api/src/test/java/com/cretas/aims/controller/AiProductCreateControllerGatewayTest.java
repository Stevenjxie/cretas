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
class AiProductCreateControllerGatewayTest {

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
    private AiProductCreateController controller;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new AiProductCreateController(
                gateway, principalFactory, previewTokenService, objectMapper);
        principal = new ExecutionPrincipal(
                FACTORY_ID,
                "FACTORY",
                Long.toString(USER_ID),
                PrincipalType.USER,
                Set.of("dispatcher"),
                Set.of(),
                Set.of());
    }

    @Test
    void cookieBackedTrustedAttributesPreviewThroughGatewayAndIssueOpaqueProof() {
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        when(gateway.execute(any())).thenReturn(gatewaySuccess(
                ToolExecutionMode.PREVIEW, Map.of("status", "PREVIEW", "productName", "SKU-A")));
        IntentPreviewToken token = boundToken(
                Map.of("productName", "SKU-A"), "proof-product");
        when(previewTokenService.createBoundToken(any())).thenReturn(token);

        Map<?, ?> data = (Map<?, ?>) controller.preview(
                FACTORY_ID, Map.of("productName", "SKU-A"), request).getData();

        assertThat(data.get("confirmationToken")).isEqualTo("proof-product");
        assertThat(data.get("confirmationExpiresAt")).isEqualTo(token.getExpiresAt().toString());
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().mode()).isEqualTo(ToolExecutionMode.PREVIEW);
        assertThat(command.getValue().source()).isEqualTo(ToolExecutionSource.HTTP_CONTROLLER);
        assertThat(command.getValue().principal()).isSameAs(principal);
        assertThat(command.getValue().confirmationProof()).isEmpty();
        ArgumentCaptor<PreviewTokenService.BoundTokenRequest> tokenRequest =
                ArgumentCaptor.forClass(PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokenService).createBoundToken(tokenRequest.capture());
        assertThat(tokenRequest.getValue().parameters()).containsEntry("productName", "SKU-A");
        assertThat(tokenRequest.getValue().executionMode()).isEqualTo(ToolExecutionMode.EXECUTE);
    }

    @Test
    void executeRequiresExactBoundProofAndPassesConfirmationAndStableIdempotencyToGateway() {
        Map<String, Object> body = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(body, "proof-product");
        when(previewTokenService.validateTokenForUser("proof-product", USER_ID))
                .thenReturn(Optional.of(token));
        when(gateway.execute(any())).thenReturn(gatewaySuccess(
                ToolExecutionMode.EXECUTE, Map.of("productId", "P-1", "productName", "SKU-A")));

        Map<?, ?> data = (Map<?, ?>) controller.create(
                FACTORY_ID, "proof-product", body, request).getData();

        assertThat(data.get("productId")).isEqualTo("P-1");
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        ToolExecutionCommand actual = command.getValue();
        assertThat(actual.mode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(actual.confirmationProof()).get().satisfies(proof -> {
            assertThat(proof.proofToken()).isEqualTo("proof-product");
            assertThat(proof.commandDigest()).isEqualTo(token.getCommandDigest());
        });
        assertThat(actual.idempotencyKey()).contains(
                "fixed-http-" + ToolCommandDigest.persistentSecretFingerprint("proof-product"));
    }

    @Test
    void parameterDriftAndReplayFailClosedBeforeGateway() {
        Map<String, Object> previewBody = Map.of("productName", "SKU-A");
        trustedAttributes(FACTORY_ID, USER_ID, "dispatcher");
        IntentPreviewToken token = boundToken(previewBody, "proof-product");
        when(previewTokenService.validateTokenForUser("proof-product", USER_ID))
                .thenReturn(Optional.of(token), Optional.empty());

        assertThatThrownBy(() -> controller.create(
                FACTORY_ID,
                "proof-product",
                Map.of("productName", "SKU-B"),
                request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(409);
        assertThatThrownBy(() -> controller.create(
                FACTORY_ID, "proof-product", previewBody, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(409);
        verify(gateway, never()).execute(any());
    }

    @Test
    void pathFactoryMismatchRejectsEvenWhenAuthorizationHeaderClaimsAnotherTenant() {
        trustedAttributes("F007", USER_ID, "dispatcher");

        assertThatThrownBy(() -> controller.preview(
                FACTORY_ID, Map.of("productName", "SKU-A"), request))
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

    private IntentPreviewToken boundToken(Map<String, Object> body, String proofToken) {
        String digest = ToolCommandDigest.commandDigest(
                FACTORY_ID,
                USER_ID,
                "product_create",
                "1.0.0",
                ToolExecutionMode.EXECUTE,
                objectMapper.valueToTree(body));
        return IntentPreviewToken.builder()
                .token(proofToken)
                .factoryId(FACTORY_ID)
                .tenantId(FACTORY_ID)
                .userId(USER_ID)
                .toolName("product_create")
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
                "product_create",
                "1.0.0",
                "audit-" + mode,
                "trace-" + mode,
                ToolExecutionStatus.SUCCEEDED,
                objectMapper.valueToTree(Map.of("success", true, "data", data)),
                "Tool succeeded",
                false);
    }
}
