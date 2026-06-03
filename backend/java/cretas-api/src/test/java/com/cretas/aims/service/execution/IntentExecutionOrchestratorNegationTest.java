package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * W1b follow-up — VETO gate in the EXECUTION orchestrator.
 *
 * <p>The orchestrator's own contains-based phrase shortcut would execute "看订单" embedded
 * inside the negated read "别给我看订单", bypassing the recognition-layer veto. The fix adds an
 * early VETO gate to {@code execute()}; this test exercises the clarification response builder
 * that the gate returns for a VETO_READ — asserting nothing is recognized or executed.
 *
 * <p>Construction mirrors {@code ToolDispatchServiceBuildNoToolResponseTest}: all collaborators
 * are mocked because {@code buildNegationVetoClarificationResponse} uses none of them (only the
 * response builder + a timestamp).
 */
@DisplayName("IntentExecutionOrchestrator — W1b negation VETO clarification (execution layer)")
class IntentExecutionOrchestratorNegationTest {

    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class),
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
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
    }

    @Test
    @DisplayName("VETO_READ clarification: intentRecognized=false, NEED_CLARIFICATION, message present, no intentCode/execution")
    void buildNegationVetoClarificationResponse_isClarificationWithNoExecution() {
        IntentExecuteResponse response =
                orchestrator.buildNegationVetoClarificationResponse("别给我看订单");

        // Nothing recognized → nothing executed.
        assertThat(response.getIntentRecognized()).isFalse();
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");

        // Message present and identical across message + formattedText (UI surfaces both).
        String expectedMsg = "您是要取消这次操作吗?需要我帮您查询或处理什么?";
        assertThat(response.getMessage()).isEqualTo(expectedMsg);
        assertThat(response.getFormattedText()).isEqualTo(expectedMsg);

        // No intent was bound and no execution happened.
        assertThat(response.getIntentCode()).isNull();
        assertThat(response.getExecutedAt()).isNotNull();
    }
}
