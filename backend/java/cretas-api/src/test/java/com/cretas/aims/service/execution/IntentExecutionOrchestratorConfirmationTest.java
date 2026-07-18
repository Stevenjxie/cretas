package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentExecutionOrchestratorConfirmationTest {

    private static final String FACTORY = "F001";
    private static final long USER = 12L;
    private static final String ROLE = "FACTORY_ADMIN";
    private static final String TOKEN = "sensitive-token";
    private static final String CLAIM = "claim-owner";

    private AIIntentService aiIntentService;
    private ToolRegistry toolRegistry;
    private ToolDispatchService dispatchService;
    private PreviewTokenService tokenService;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolRegistry = mock(ToolRegistry.class);
        dispatchService = mock(ToolDispatchService.class);
        tokenService = mock(PreviewTokenService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                toolRegistry,
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                dispatchService,
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", tokenService);
        when(tokenService.claimToken(TOKEN, FACTORY, USER)).thenReturn(claimResult());
    }

    @Test
    void missingIntentConfigFailsTheClaimBeforeToolExecution() {
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.empty());

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, TOKEN, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "意图配置不存在: ORDER_CREATE");
    }

    @Test
    void missingToolAndVersionDriftBothFinishAsFailed() {
        AIIntentConfig config = intentConfig();
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        when(toolRegistry.getExecutor("order_create")).thenReturn(Optional.empty());

        IntentExecuteResponse missing = orchestrator.confirm(FACTORY, TOKEN, USER, ROLE);
        assertThat(missing.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "未找到绑定工具: order_create");
    }

    @Test
    void failedToolResponseWritesFailedTerminalState() {
        ToolExecutor tool = configuredTool();
        when(dispatchService.executeWithTool(
                eq(tool), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("FAILED").message("business rejected").build());
        when(tokenService.resolveClaim(TOKEN, CLAIM, false, "business rejected")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, TOKEN, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "business rejected");
    }

    @Test
    void successfulToolResponseConfirmsOnlyAfterDispatchAndOverridesSpoofedContext() {
        ToolExecutor tool = configuredTool();
        ArgumentCaptor<IntentExecuteRequest> requestCaptor = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        when(dispatchService.executeWithTool(
                eq(tool), eq(FACTORY), requestCaptor.capture(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("SUCCESS").message("created").build());
        when(tokenService.resolveClaim(TOKEN, CLAIM, true, "created")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, TOKEN, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> context = requestCaptor.getValue().getContext();
        assertThat(context)
                .containsEntry("factoryId", FACTORY)
                .containsEntry("factory_id", FACTORY)
                .containsEntry("userId", USER)
                .containsEntry("user_id", USER)
                .containsEntry("userRole", ROLE)
                .containsEntry("role", ROLE)
                .containsEntry("confirmed", true);
        verify(tokenService).resolveClaim(TOKEN, CLAIM, true, "created");
    }

    private ToolExecutor configuredTool() {
        AIIntentConfig config = intentConfig();
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getVersion()).thenReturn("1.0.0");
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        when(toolRegistry.getExecutor("order_create")).thenReturn(Optional.of(tool));
        return tool;
    }

    private AIIntentConfig intentConfig() {
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode("ORDER_CREATE");
        config.setToolName("order_create");
        return config;
    }

    private ClaimResult claimResult() {
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(TOKEN)
                .factoryId(FACTORY)
                .tenantId(FACTORY)
                .userId(USER)
                .intentCode("ORDER_CREATE")
                .toolName("order_create")
                .descriptorVersion("1.0.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .status(TokenStatus.EXECUTING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        Map<String, Object> spoofed = new HashMap<>();
        spoofed.put("factoryId", "EVIL");
        spoofed.put("userId", 999L);
        spoofed.put("role", "SUPER_ADMIN");
        spoofed.put("amount", 5);
        return ClaimResult.success(token, CLAIM, spoofed);
    }
}
