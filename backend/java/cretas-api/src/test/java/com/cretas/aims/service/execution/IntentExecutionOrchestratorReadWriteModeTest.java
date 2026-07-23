package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 读写分块 (2026-07-23 spec §4) — 编排器 mode=READ 写拦截 / 意图级权限码 / aiMode 标记 /
 * demo 写闸 测试。构造方式沿用 {@code IntentExecutionOrchestratorConfirmationTest} 的全 mock 风格。
 */
@DisplayName("IntentExecutionOrchestrator — P1 读写分块 (mode/aiMode/requiredPermission/demo闸)")
class IntentExecutionOrchestratorReadWriteModeTest {

    private static final String FACTORY = "F006";
    private static final long USER = 22L;
    private static final String ROLE = "factory_super_admin";
    private static final String TOKEN = "sensitive-token";
    private static final String CLAIM = "claim-owner";
    private static final String DIGEST = "a".repeat(64);

    private AIIntentService aiIntentService;
    private ToolRegistry toolRegistry;
    private ToolDispatchService dispatchService;
    private PreviewTokenService tokenService;
    private ToolRbacGuard rbacGuard;
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
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        rbacGuard = mock(ToolRbacGuard.class);
        IntentPermissionGate gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", rbacGuard);
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
        // 与 DemoReadOnlyInterceptor 默认名单一致 (@Value 单测不生效, 手动注入)
        ReflectionTestUtils.setField(orchestrator, "demoFactoryIdsCsv", "DEMO_REST,DEMO_FACTORY");
    }

    // ==================== 意图夹具 ====================

    /** 写意图: sensitivity=HIGH → WriteGuardService.isWriteIntent=true */
    private AIIntentConfig writeIntent() {
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("MATERIAL_INBOUND_CREATE");
        intent.setIntentName("原料入库");
        intent.setIntentCategory("DATA_OP");
        intent.setSensitivityLevel("HIGH");
        intent.setToolName("material_inbound_create");
        return intent;
    }

    /** 读意图: LOW + 查询后缀 → isWriteIntent=false */
    private AIIntentConfig readIntent() {
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("MATERIAL_BATCH_QUERY");
        intent.setIntentName("批次查询");
        intent.setIntentCategory("ANALYSIS");
        intent.setSensitivityLevel("LOW");
        intent.setToolName("material_batch_query");
        return intent;
    }

    private IntentExecuteRequest explicitRequest(String intentCode, String mode) {
        return IntentExecuteRequest.builder()
                .userInput("测试输入")
                .intentCode(intentCode)
                .mode(mode)
                .build();
    }

    // ==================== 1. mode=READ 写拦截 ====================

    @Test
    @DisplayName("mode=READ + 写意图(HIGH) → READ_MODE_WRITE_BLOCKED + aiMode=WRITE, 工具零执行")
    void readModeBlocksWriteIntentWithoutExecution() {
        AIIntentConfig intent = writeIntent();
        when(aiIntentService.getIntentByCode(FACTORY, "MATERIAL_INBOUND_CREATE"))
                .thenReturn(Optional.of(intent));
        when(aiIntentService.hasPermission("MATERIAL_INBOUND_CREATE", ROLE)).thenReturn(true);

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(
                FACTORY, explicitRequest("MATERIAL_INBOUND_CREATE", "READ"), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getIntentCode()).isEqualTo("MATERIAL_INBOUND_CREATE");
        assertThat(response.getMessage()).isEqualTo("这是操作类请求，请切换到【操作】页处理。");
        verify(dispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== 2. mode=null 旧行为不变 ====================

    @Test
    @DisplayName("mode=null (老客户端) + 写意图 → 原 WRITE_CONFIRM_REQUIRED 确认流不变, 且 aiMode=WRITE")
    void nullModeKeepsLegacyWriteConfirmFlow() {
        AIIntentConfig intent = writeIntent();
        when(aiIntentService.getIntentByCode(FACTORY, "MATERIAL_INBOUND_CREATE"))
                .thenReturn(Optional.of(intent));
        when(aiIntentService.hasPermission("MATERIAL_INBOUND_CREATE", ROLE)).thenReturn(true);

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(
                FACTORY, explicitRequest("MATERIAL_INBOUND_CREATE", null), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        verify(dispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== 3/4. required_permission 权限码判定 ====================

    @Test
    @DisplayName("requiredPermission=inventory:write + 矩阵拒绝 → NO_PERMISSION + requiredPermission 回带")
    void permissionCodeDenyReturnsRequiredPermission() {
        AIIntentConfig intent = writeIntent();
        intent.setRequiredPermission("inventory:write");
        when(aiIntentService.getIntentByCode(FACTORY, "MATERIAL_INBOUND_CREATE"))
                .thenReturn(Optional.of(intent));
        when(rbacGuard.hasAnyPermission(any(), eq("inventory:write"))).thenReturn(false);

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(
                FACTORY, explicitRequest("MATERIAL_INBOUND_CREATE", null), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(response.getRequiredPermission()).isEqualTo("inventory:write");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        // 权限码路径优先, 不再走 requiredRoles 旧逻辑
        verify(aiIntentService, never()).hasPermission(any(), any());
        verify(dispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("requiredPermission 已设 + 矩阵放行 → 正常继续执行 (读意图直达工具)")
    void permissionCodeAllowProceedsToExecution() {
        AIIntentConfig intent = readIntent();
        intent.setRequiredPermission("inventory:read");
        when(aiIntentService.getIntentByCode(FACTORY, "MATERIAL_BATCH_QUERY"))
                .thenReturn(Optional.of(intent));
        when(rbacGuard.hasAnyPermission(any(), eq("inventory:read"))).thenReturn(true);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("material_batch_query")).thenReturn(Optional.of(tool));
        when(dispatchService.executeWithTool(eq(tool), eq(FACTORY), any(), eq(intent), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("SUCCESS").message("查询到 3 条批次").build());

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(
                FACTORY, explicitRequest("MATERIAL_BATCH_QUERY", "OPERATE"), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(dispatchService).executeWithTool(eq(tool), eq(FACTORY), any(), eq(intent), eq(USER), eq(ROLE), eq(null));
    }

    // ==================== 5. aiMode 标记 ====================

    @Test
    @DisplayName("读意图正常执行 → 响应 aiMode=READ (6.95 终点 stamp)")
    void readIntentIsStampedAiModeRead() {
        AIIntentConfig intent = readIntent();
        when(aiIntentService.getIntentByCode(FACTORY, "MATERIAL_BATCH_QUERY"))
                .thenReturn(Optional.of(intent));
        when(aiIntentService.hasPermission("MATERIAL_BATCH_QUERY", ROLE)).thenReturn(true);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("material_batch_query")).thenReturn(Optional.of(tool));
        when(dispatchService.executeWithTool(eq(tool), eq(FACTORY), any(), eq(intent), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("SUCCESS").message("查询到 3 条批次").build());

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(
                FACTORY, explicitRequest("MATERIAL_BATCH_QUERY", null), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAiMode()).isEqualTo("READ");
    }

    // ==================== 6. demo 写闸 (confirm 二阶段) ====================

    @Test
    @DisplayName("confirm() + factoryId=DEMO_REST → DEMO_WRITE_BLOCKED, 工具零执行, claim 已终结")
    void demoFactoryConfirmIsBlockedWithoutExecution() {
        when(tokenService.claimToken(TOKEN, "DEMO_REST", USER, DIGEST))
                .thenReturn(claimResult("DEMO_REST"));

        IntentExecuteResponse response = orchestrator.confirm("DEMO_REST", proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("DEMO_WRITE_BLOCKED");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getMessage()).contains("演示环境不执行真实写入");
        verify(dispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "演示环境拦截: 不执行真实写入");
    }

    @Test
    @DisplayName("confirm() + 非 demo 工厂 (F006) → 正常执行")
    void nonDemoFactoryConfirmExecutes() {
        when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST)).thenReturn(claimResult(FACTORY));
        AIIntentConfig config = writeIntent();
        config.setIntentCode("ORDER_CREATE");
        config.setToolName("order_create");
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getVersion()).thenReturn("1.0.0");
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        when(toolRegistry.getExecutor("order_create")).thenReturn(Optional.of(tool));
        when(dispatchService.executeWithTool(
                eq(tool), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("SUCCESS").message("created").build());
        when(tokenService.resolveClaim(TOKEN, CLAIM, true, "created")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(dispatchService).executeWithTool(
                eq(tool), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(null));
    }

    // ==================== 夹具 ====================

    private ClaimResult claimResult(String factoryId) {
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(TOKEN)
                .factoryId(factoryId)
                .tenantId(factoryId)
                .userId(USER)
                .intentCode("ORDER_CREATE")
                .toolName("order_create")
                .descriptorVersion("1.0.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .status(TokenStatus.EXECUTING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        return ClaimResult.success(token, CLAIM, new HashMap<>(Map.of("amount", 5)));
    }

    private ConfirmationProof proof() {
        return new ConfirmationProof(TOKEN, DIGEST, Instant.now().plusSeconds(300));
    }
}
