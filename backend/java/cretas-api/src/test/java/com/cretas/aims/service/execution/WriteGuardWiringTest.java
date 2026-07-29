package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W0 write-guard WIRING tests (intent-w0 Task 2).
 *
 * <p>The whole point of W0: a misroute to a destructive operation MUST NOT silently execute,
 * INCLUDING via the {@code forceExecute=true} multi-intent / conversation-continuation bypass.
 *
 * <p>CRITICAL invariant under test: the guard is NOT conditioned on {@code request.getForceExecute()}.
 * The multi-intent and conversation-continuation paths hard-set forceExecute=true — that flag must
 * never let a write intent skip the guard. These tests use the REAL (stateless) {@link WriteGuardService}
 * wired into a mock-collaborator {@link IntentExecutionOrchestrator}, and verify that a forced write
 * intent without a confirmed signal NEVER reaches {@code toolDispatchService.executeWithTool(...)}.
 *
 * <p>Also includes a focused {@link ToolDispatchService}-level test proving Site B blocks a write tool
 * without confirm (defense-in-depth choke point for the direct-Tool execution branch).
 */
@DisplayName("W0 write-guard wiring — forceExecute cannot bypass")
class WriteGuardWiringTest {

    // Real, stateless guard — the actual logic under test.
    private final WriteGuardService writeGuard = new WriteGuardService();

    private IntentExecutionOrchestrator orchestrator;

    // Collaborators we assert against / drive.
    private AIIntentService aiIntentService;
    private ToolDispatchService toolDispatchService;
    private ToolRegistry toolRegistry;
    private BusinessTypeGate businessTypeGate;

    private static final String FACTORY = "F001";
    private static final Long USER_ID = 22L;
    private static final String ROLE = "factory_super_admin";

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolDispatchService = mock(ToolDispatchService.class);
        toolRegistry = mock(ToolRegistry.class);
        businessTypeGate = mock(BusinessTypeGate.class);

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
                toolDispatchService,
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));

        // Field-injected dependencies (set via reflection since they are not constructor args).
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", writeGuard);
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);

        // hasPermission → always true (permission is NOT what should block a write here).
        when(aiIntentService.hasPermission(anyString(), any())).thenReturn(true);
        // BusinessTypeGate → never gates (universal intents).
        when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
    }

    /** HIGH-sensitivity write intent that does NOT trigger approval (needsApproval requires CRITICAL+requiresApproval). */
    private AIIntentConfig writeIntent(String tool) {
        return AIIntentConfig.builder()
                .intentCode("MATERIAL_BATCH_DELETE")
                .intentName("删除物料批次")
                .intentCategory("DATA_OP")
                .sensitivityLevel("HIGH")     // → isWriteIntent == true
                .requiresApproval(false)      // → needsApproval() == false (so W0 is the blocker, not approval)
                .toolName(tool)
                .build();
    }

    private AIIntentConfig readIntent(String tool) {
        return AIIntentConfig.builder()
                .intentCode("MATERIAL_BATCH_QUERY")
                .intentName("查询物料批次")
                .intentCategory("DATA_OP")
                .sensitivityLevel("LOW")      // → isWriteIntent == false
                .requiresApproval(false)
                .toolName(tool)
                .build();
    }

    private void stubIntent(AIIntentConfig intent) {
        when(aiIntentService.getIntentByCode(eq(FACTORY), eq(intent.getIntentCode())))
                .thenReturn(Optional.of(intent));
        when(toolRegistry.getExecutor(eq(intent.getToolName())))
                .thenReturn(Optional.of(mock(ToolExecutor.class)));
    }

    private void stubDispatchReturns() {
        when(toolDispatchService.executeWithTool(any(), anyString(), any(), any(), anyLong(), anyString(), any()))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").build());
    }

    // ================= (a) CRITICAL: forceExecute write blocked =================

    @Test
    @DisplayName("(a) forceExecute=true write intent WITHOUT confirm → BLOCKED; dispatch never called")
    void forceExecuteWrite_withoutConfirm_isBlocked_andNeverDispatches() {
        AIIntentConfig intent = writeIntent("material_batch_delete");
        stubIntent(intent);

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .forceExecute(true)   // <-- the multi-intent bypass flag; MUST NOT skip the guard
                .build();

        IntentExecuteResponse response =
                orchestrator.executeWithExplicitIntent(FACTORY, request, USER_ID, ROLE);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(response.getIntentCode()).isEqualTo("MATERIAL_BATCH_DELETE");
        assertThat(response.getRequiresApproval()).isTrue();
        // The destructive operation must NEVER have executed.
        verify(toolDispatchService, never())
                .executeWithTool(any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    // ================= (b) confirmed=true → not blocked =================

    @Test
    @DisplayName("(b) same write intent WITH confirmed=true in context → NOT blocked; dispatch proceeds")
    void writeIntent_withConfirmed_isNotBlocked_andDispatches() {
        AIIntentConfig intent = writeIntent("material_batch_delete");
        stubIntent(intent);
        stubDispatchReturns();

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .forceExecute(true)
                .context(Map.of("confirmed", true))   // user already confirmed
                .build();

        IntentExecuteResponse response =
                orchestrator.executeWithExplicitIntent(FACTORY, request, USER_ID, ROLE);

        assertThat(response.getStatus()).isNotEqualTo("WRITE_CONFIRM_REQUIRED");
        verify(toolDispatchService, times(1))
                .executeWithTool(any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    // ================= (c) READ intent → not blocked =================

    @Test
    @DisplayName("(c) READ intent (LOW sensitivity) → NOT blocked; dispatch proceeds")
    void readIntent_isNotBlocked_andDispatches() {
        AIIntentConfig intent = readIntent("material_batch_query");
        stubIntent(intent);
        stubDispatchReturns();

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .build();

        IntentExecuteResponse response =
                orchestrator.executeWithExplicitIntent(FACTORY, request, USER_ID, ROLE);

        assertThat(response.getStatus()).isNotEqualTo("WRITE_CONFIRM_REQUIRED");
        verify(toolDispatchService, times(1))
                .executeWithTool(any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("收入管理报表生成是非破坏性报表产物生成，不应被 _GENERATE 写保护误拦")
    void revenueReportGenerate_isNotBlockedByGenerateSuffix() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("REVENUE_REPORT_GENERATE")
                .intentName("生成收入管理报表")
                .intentCategory("RESTAURANT")
                .sensitivityLevel("LOW")
                .requiresApproval(false)
                .toolName("revenue_report_generate")
                .build();
        stubIntent(intent);
        stubDispatchReturns();

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .userInput("本月收入管理报表")
                .build();

        IntentExecuteResponse response =
                orchestrator.executeWithExplicitIntent(FACTORY, request, USER_ID, ROLE);

        assertThat(response.getStatus()).isNotEqualTo("WRITE_CONFIRM_REQUIRED");
        verify(toolDispatchService, times(1))
                .executeWithTool(any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    // ================= (d) previewOnly write → not blocked =================

    @Test
    @DisplayName("(d) previewOnly=true write intent → NOT blocked by W0 (preview ≠ execute)")
    void previewOnlyWrite_isNotBlockedByGuard() {
        AIIntentConfig intent = writeIntent("material_batch_delete");
        when(aiIntentService.getIntentByCode(eq(FACTORY), eq(intent.getIntentCode())))
                .thenReturn(Optional.of(intent));
        ToolExecutor previewTool = mock(ToolExecutor.class);
        when(previewTool.supportsPreview()).thenReturn(true);
        when(toolRegistry.getExecutor(eq(intent.getToolName()))).thenReturn(Optional.of(previewTool));
        when(toolDispatchService.executeToolPreview(any(), anyString(), any(), any(), anyLong(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("PREVIEW").build());

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .previewOnly(true)
                .forceExecute(true)
                .build();

        IntentExecuteResponse response =
                orchestrator.executeWithExplicitIntent(FACTORY, request, USER_ID, ROLE);

        // W0 must not have produced the block; the preview path should be reached instead.
        assertThat(response.getStatus()).isNotEqualTo("WRITE_CONFIRM_REQUIRED");
        verify(toolDispatchService, times(1))
                .executeToolPreview(any(), anyString(), any(), any(), anyLong(), anyString());
    }

    // ================= Focused Site B: ToolDispatchService =================

    @Test
    @DisplayName("Site B: ToolDispatchService.executeWithTool blocks a write tool without confirm (no tool.execute)")
    void siteB_toolDispatch_blocksWriteToolWithoutConfirm() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);

        ToolDispatchService dispatch = new ToolDispatchService(
                registry,
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);
        // W9: inject a permissive ToolRbacEnforcer (these tests exercise the W0 write-guard, not RBAC).
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", permissiveRbacEnforcer());

        ToolExecutor writeTool = mock(ToolExecutor.class);
        when(writeTool.getToolName()).thenReturn("material_batch_delete");
        when(writeTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);

        AIIntentConfig intent = writeIntent("material_batch_delete");
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .userInput("删除批次 B001")
                .build();

        IntentExecuteResponse response = dispatch.executeWithTool(
                writeTool, FACTORY, request, intent, USER_ID, ROLE, (IntentMatchResult) null);

        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        // The tool must never have been executed.
        verify(writeTool, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Site B: confirmed=true lets the write tool through the guard")
    void siteB_toolDispatch_confirmedAllowsWriteTool() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);

        ToolDispatchService dispatch = new ToolDispatchService(
                registry,
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);
        // W9: inject a permissive ToolRbacEnforcer (these tests exercise the W0 write-guard, not RBAC).
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", permissiveRbacEnforcer());

        ToolExecutor writeTool = mock(ToolExecutor.class);
        when(writeTool.getToolName()).thenReturn("material_batch_delete");
        when(writeTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        when(writeTool.execute(any(), any())).thenReturn("{\"success\":true,\"message\":\"已删除\"}");

        AIIntentConfig intent = writeIntent("material_batch_delete");
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .userInput("删除批次 B001")
                .context(Map.of("confirmed", true))
                .build();

        IntentExecuteResponse response = dispatch.executeWithTool(
                writeTool, FACTORY, request, intent, USER_ID, ROLE, (IntentMatchResult) null);

        // Past the guard → not the WRITE_CONFIRM_REQUIRED short-circuit.
        assertThat(response.getStatus()).isNotEqualTo("WRITE_CONFIRM_REQUIRED");
        verify(writeTool, times(1)).execute(any(), any());
    }

    // ================= Site F: LLM Tool-Calling fallback (LlmIntentFallbackClientImpl) =================

    /**
     * Site F reproduces the exact guard condition at
     * {@code LlmIntentFallbackClientImpl.executeToolCallingWorkflow} (the line immediately before
     * {@code executor.execute(toolCall, context)}). That method is private and depends on a live
     * DashScope LLM round-trip + domain-filtered tool list, so it is not unit-testable in isolation;
     * this test instead asserts the guard PREDICATE at that call path with the real stateless guard.
     *
     * <p>Why {@code Map.of()} for the confirm check: the LLM-fallback path has NO user confirmation
     * step — {@code buildToolExecutionContext} sets no confirm flag — so a write tool selected
     * autonomously by the LLM is unconfirmed by construction and MUST be blocked.
     */
    @Test
    @DisplayName("Site F: LLM-fallback guard blocks an LLM-selected write tool (no confirm) → throws, execute() never reached")
    void siteF_llmFallback_blocksWriteToolWithoutConfirm() throws Exception {
        ToolExecutor llmSelectedWriteTool = mock(ToolExecutor.class);
        when(llmSelectedWriteTool.getActionType()).thenReturn(ToolExecutor.ActionType.DELETE);
        String toolName = "material_batch_delete";

        // Exact Site F guard expression (LlmIntentFallbackClientImpl): isWriteTool && !isConfirmed(Map.of()).
        boolean blocked = false;
        try {
            if (writeGuard.isWriteTool(llmSelectedWriteTool) && !writeGuard.isConfirmed(Map.of())) {
                throw new IllegalStateException(
                        "W0 write-guard: tool '" + toolName + "' 需要显式确认后才能执行");
            }
            // Only reached if NOT blocked — would be the real execute() call site.
            llmSelectedWriteTool.execute(any(), any());
        } catch (IllegalStateException e) {
            blocked = true;
            assertThat(e.getMessage()).contains("W0 write-guard").contains(toolName);
        }

        assertThat(blocked).as("LLM-selected write tool must be blocked at Site F").isTrue();
        // The destructive operation must NEVER have executed.
        verify(llmSelectedWriteTool, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Site F: a READ tool (ANALYZE/READ actionType) is NOT blocked by the LLM-fallback guard")
    void siteF_llmFallback_readToolNotBlocked() {
        ToolExecutor llmSelectedReadTool = mock(ToolExecutor.class);
        when(llmSelectedReadTool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        // spec §8.2: 读工具必须显式声明 READ —— 未声明按 WRITE 是新的安全底线, 见
        // ToolAccessModeDeclarationTest#undeclaredToolIsTreatedAsWrite
        when(llmSelectedReadTool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);

        // Same predicate; a READ tool must fall through (isWriteTool == false).
        boolean wouldBlock = writeGuard.isWriteTool(llmSelectedReadTool)
                && !writeGuard.isConfirmed(Map.of());

        assertThat(wouldBlock).as("READ tool must NOT be blocked by the LLM-fallback write-guard").isFalse();
    }

    /**
     * A permissive {@link ToolRbacEnforcer} that allows every tool (its guard's PermissionService
     * grants all). Used where the test exercises the W0 write-guard, not W9 RBAC.
     */
    private static ToolRbacEnforcer permissiveRbacEnforcer() {
        com.cretas.aims.service.PermissionService perm = mock(com.cretas.aims.service.PermissionService.class);
        when(perm.hasAnyPermission(any(), any(String[].class))).thenReturn(true);
        com.cretas.aims.repository.UserRepository userRepo = mock(com.cretas.aims.repository.UserRepository.class);
        com.cretas.aims.entity.User u = new com.cretas.aims.entity.User();
        u.setId(USER_ID);
        when(userRepo.findById(any())).thenReturn(Optional.of(u));
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", userRepo);
        ReflectionTestUtils.setField(guard, "permissionService", perm);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        return enforcer;
    }
}
