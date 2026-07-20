package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolDispatchServiceGatewayMigrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolRegistry toolRegistry;
    private ToolCallRedundancyService redundancyService;
    private ToolExecutionGateway gateway;
    private LegacyToolMigrationRegistry migrationRegistry;
    private AuthenticatedToolPrincipalFactory principalFactory;
    private ToolExecutor tool;
    private ToolDispatchService dispatch;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        redundancyService = mock(ToolCallRedundancyService.class);
        gateway = mock(ToolExecutionGateway.class);
        migrationRegistry = mock(LegacyToolMigrationRegistry.class);
        principalFactory = mock(AuthenticatedToolPrincipalFactory.class);
        tool = mock(ToolExecutor.class);

        when(tool.getToolName()).thenReturn("restaurant_dish_list");
        when(tool.getParametersSchema()).thenReturn(Map.of(
                "type", "object", "properties", Map.of(), "required", java.util.List.of()));
        when(toolRegistry.isToolEnabledForFactory("R-1", "restaurant_dish_list"))
                .thenReturn(true);
        when(migrationRegistry.contains("restaurant_dish_list")).thenReturn(true);
        when(migrationRegistry.expectedVersion("restaurant_dish_list"))
                .thenReturn(Optional.of("1.0.0"));
        when(principalFactory.create("R-1", 42L, "restaurant_manager"))
                .thenReturn(new ExecutionPrincipal(
                        "R-1", "RESTAURANT", "42", PrincipalType.USER,
                        Set.of("restaurant_manager"), Set.of(), Set.of()));

        dispatch = new ToolDispatchService(
                toolRegistry,
                objectMapper,
                null,
                redundancyService,
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "toolExecutionGateway", gateway);
        ReflectionTestUtils.setField(dispatch, "legacyToolMigrationRegistry", migrationRegistry);
        ReflectionTestUtils.setField(
                dispatch, "authenticatedToolPrincipalFactory", principalFactory);
    }

    @Test
    void enabledCandidateUsesGatewayOnceSkipsLegacyCacheRetryAndPreservesNeedMoreInfo() throws Exception {
        ReflectionTestUtils.setField(dispatch, "intentDispatchGatewayMigrationEnabled", true);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            ToolExecutionCommand command = invocation.getArgument(0);
            return new ToolExecutionResult(
                    command.requestId(),
                    command.toolName(),
                    command.expectedDescriptorVersion(),
                    "audit-1",
                    command.traceId(),
                    ToolExecutionStatus.FAILED,
                    JsonNodeFactory.instance.objectNode()
                            .put("success", false)
                            .put("status", "NEED_MORE_INFO")
                            .put("needMoreInfo", true)
                            .put("message", "请选择门店"),
                    "Tool execution failed",
                    false);
        });

        IntentExecuteResponse response = execute();

        assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(response.getMessage()).isEqualTo("请选择门店");
        assertThat(response.getMetadata())
                .containsEntry("executionBoundary", "TOOL_EXECUTION_GATEWAY")
                .containsEntry("gatewayStatus", "FAILED")
                .containsEntry("gatewayAuditEventId", "audit-1");
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().source())
                .isEqualTo(ToolExecutionSource.AI_INTENT_DISPATCH);
        assertThat(command.getValue().parameters().path("factoryId").asText())
                .isEqualTo("R-1");
        assertThat(command.getValue().idempotencyKey()).isEmpty();
        assertThat(command.getValue().confirmationProof()).isEmpty();
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
        verifyNoInteractions(redundancyService);
    }

    @Test
    void gatewayDenialNeverFallsBackToDirectExecution() throws Exception {
        ReflectionTestUtils.setField(dispatch, "intentDispatchGatewayMigrationEnabled", true);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            ToolExecutionCommand command = invocation.getArgument(0);
            return new ToolExecutionResult(
                    command.requestId(), command.toolName(), command.expectedDescriptorVersion(),
                    "audit-denied", command.traceId(), ToolExecutionStatus.DENIED,
                    JsonNodeFactory.instance.objectNode(), "Tool policy rejected", false);
        });

        IntentExecuteResponse response = execute();

        assertThat(response.getStatus()).isEqualTo("DENIED");
        assertThat(response.getMessage()).isEqualTo("Tool policy rejected");
        verify(gateway).execute(any());
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
        verifyNoInteractions(redundancyService);
    }

    @Test
    void gatewayTimeoutNeverFallsBackToDirectExecution() throws Exception {
        ReflectionTestUtils.setField(dispatch, "intentDispatchGatewayMigrationEnabled", true);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            ToolExecutionCommand command = invocation.getArgument(0);
            return new ToolExecutionResult(
                    command.requestId(), command.toolName(), command.expectedDescriptorVersion(),
                    "audit-timeout", command.traceId(), ToolExecutionStatus.TIMEOUT,
                    JsonNodeFactory.instance.objectNode(), "Tool execution deadline expired", false);
        });

        IntentExecuteResponse response = execute();

        assertThat(response.getStatus()).isEqualTo("TIMEOUT");
        verify(gateway).execute(any());
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
        verifyNoInteractions(redundancyService);
    }

    @Test
    void gatewayExceptionNeverFallsBackToDirectExecution() throws Exception {
        ReflectionTestUtils.setField(dispatch, "intentDispatchGatewayMigrationEnabled", true);
        when(gateway.execute(any())).thenThrow(new IllegalStateException("gateway unavailable"));

        IntentExecuteResponse response = execute();

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(gateway).execute(any());
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
        verifyNoInteractions(redundancyService);
    }

    @Test
    void defaultDisabledFlagKeepsLegacyDirectPath() throws Exception {
        when(redundancyService.isRedundant(anyString(), anyString(), anyMap()))
                .thenReturn(false);
        when(redundancyService.computeParametersHash(anyMap())).thenReturn("hash");
        when(tool.execute(any(ToolCall.class), anyMap()))
                .thenReturn("{\"success\":true,\"message\":\"legacy path\"}");

        IntentExecuteResponse response = execute();

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMessage()).isEqualTo("legacy path");
        verify(gateway, never()).execute(any());
        verify(tool).execute(any(ToolCall.class), anyMap());
    }

    private IntentExecuteResponse execute() {
        return dispatch.executeWithTool(
                tool,
                "R-1",
                IntentExecuteRequest.builder()
                        .userInput("列出菜品")
                        .context(Map.of())
                        .build(),
                AIIntentConfig.builder()
                        .intentCode("RESTAURANT_DISH_LIST")
                        .intentName("菜品列表")
                        .intentCategory("RESTAURANT")
                        .toolName("restaurant_dish_list")
                        .build(),
                42L,
                "restaurant_manager",
                null);
    }
}
