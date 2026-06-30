package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("IntentExecutionOrchestrator — restaurant ops gold deterministic route")
class RestaurantOpsGoldRouteTest {

    private IntentExecutionOrchestrator orchestrator;
    private AIIntentService aiIntentService;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
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
    @DisplayName("routes stock shortage, wastage, requisition, margin, and sales questions to RESTAURANT_OPS intents")
    void routesCoreRestaurantOpsQuestions() {
        assertThat(orchestrator.matchRestaurantOpsIntent("最近哪些食材盘亏最严重", "RESTAURANT"))
                .contains("RESTAURANT_OPS_STOCK_SHORTAGE");
        assertThat(orchestrator.matchRestaurantOpsIntent("损耗金额排名和原因占比", "RESTAURANT"))
                .contains("RESTAURANT_OPS_WASTAGE_TOP");
        assertThat(orchestrator.matchRestaurantOpsIntent("领料成本趋势和哪些食材用得多", "RESTAURANT"))
                .contains("RESTAURANT_OPS_REQUISITION_TREND");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪些菜毛利最高，有什么建议", "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪家店最赚钱，需要复盘哪家店", "RESTAURANT"))
                .contains("RESTAURANT_OPS_STORE_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪家门店净赚最多", "RESTAURANT"))
                .contains("RESTAURANT_OPS_STORE_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("总营收和客单价表现怎么样", "RESTAURANT"))
                .contains("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(orchestrator.matchRestaurantOpsIntent("周末周中营业额对比如何", "RESTAURANT"))
                .contains("RESTAURANT_WEEKDAY_WEEKEND");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪个月营收最高，为什么", "RESTAURANT"))
                .contains("RESTAURANT_PEAK_MONTH");
        assertThat(orchestrator.matchRestaurantOpsIntent("慢销菜品有哪些，怎么处理", "RESTAURANT"))
                .contains("RESTAURANT_DISH_SLOW");
    }

    @Test
    @DisplayName("does not route restaurant ops phrases for manufacturing factories")
    void doesNotRouteForFactoryDomain() {
        assertThat(orchestrator.matchRestaurantOpsIntent("总营收和客单价表现怎么样", "FACTORY"))
                .isEmpty();
    }

    @Test
    @DisplayName("DEMO_REST is forced to RESTAURANT domain even if domain resolver returns FACTORY")
    void demoRestForcesRestaurantDomain() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("FACTORY");

        AIIntentConfig salesSummary = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                .intentName("餐饮营收汇总分析")
                .intentCategory("SMARTBI")
                .toolName("restaurant_ops_gold_analysis")
                .businessType("RESTAURANT")
                .build();
        when(aiIntentService.getIntentByCode(eq("DEMO_REST"), eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.of(salesSummary));

        IntentMatchResult result = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "tryOrchestratorPhraseShortcut",
                "总营收和客单价表现怎么样",
                "DEMO_REST");

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch().getIntentCode()).isEqualTo("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(result.getBestMatch().getToolName()).isEqualTo("restaurant_ops_gold_analysis");
    }
}
