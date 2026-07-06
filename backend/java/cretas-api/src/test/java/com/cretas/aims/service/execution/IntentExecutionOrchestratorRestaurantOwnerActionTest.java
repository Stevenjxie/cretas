package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
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

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("IntentExecutionOrchestrator restaurant owner-action domain guard")
class IntentExecutionOrchestratorRestaurantOwnerActionTest {

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
    void allowsRestaurantDemoAndRestaurantPrefixesWithoutPollutingFactoryTenants() {
        assertThat(orchestrator.isRestaurantOwnerActionFactory("DEMO_REST", null)).isTrue();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("res_3101_009", null)).isTrue();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("REST_SHOP_001", null)).isTrue();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("ANY_ID", "RESTAURANT")).isTrue();

        assertThat(orchestrator.isRestaurantOwnerActionFactory("F006", null)).isFalse();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("F006", "FACTORY")).isFalse();
        assertThat(orchestrator.isRestaurantOwnerActionFactory(null, null)).isFalse();
    }

    @Test
    void matchesBossDecisionQuestionsWithUnicodeSafeKeywords() {
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u4eca\u5929\u684c\u578b\u548c\u6392\u73ed\u600e\u4e48\u8c03\uff0c\u4e8c\u4eba\u684c\u56db\u4eba\u684c\u600e\u4e48\u5b89\u6392\uff1f"))
                .isTrue();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u8fd9\u4e2a\u661f\u671f\u8425\u6536\u6bd4\u4e0a\u5468\u4f4e\uff0c\u7ed3\u5408\u8bc4\u8bba\u548c\u83dc\u54c1\u6bdb\u5229\u7ed9\u6211\u76f4\u63a5\u5efa\u8bae"))
                .isTrue();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u6839\u636e\u83dc\u54c1\u6bdb\u5229\u548c\u6210\u672c\uff0c\u5e2e\u6211\u7b97\u4e00\u4e2a\u9002\u5408\u4eca\u5929\u63a8\u7684\u5c0f\u5957\u9910"))
                .isTrue();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u4eca\u5929\u5546\u5708\u5ba2\u6d41\u753b\u50cf\u5bf9\u95e8\u5e97\u7ecf\u8425\u6709\u4ec0\u4e48\u5f71\u54cd\uff1f"))
                .isTrue();

        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u67e5\u4e00\u4e0b\u8ba2\u5355\u660e\u7ec6"))
                .isFalse();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u67e5\u8be2\u672c\u5468\u8425\u6536"))
                .isFalse();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u4eca\u5929\u67e5\u8ba2\u5355"))
                .isFalse();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u5ba2\u6237\u8bc4\u4ef7\u600e\u4e48\u6837"))
                .isFalse();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u5dee\u8bc4\u5e94\u8be5\u600e\u4e48\u6539\u5584"))
                .isFalse();
        assertThat(orchestrator.matchesOwnerActionKeywordHeuristic(
                "\u670d\u52a1\u5dee\u8bc4\u600e\u4e48\u57f9\u8bad\u5458\u5de5"))
                .isTrue();
    }

    @Test
    void routesDemoRestaurantBossQuestionBeforeGenericClarification() {
        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "DEMO_REST",
                "\u4eca\u5929\u684c\u578b\u548c\u6392\u73ed\u600e\u4e48\u8c03\uff0c\u4e8c\u4eba\u684c\u56db\u4eba\u684c\u600e\u4e48\u5b89\u6392\uff1f",
                Collections.emptyMap()))
                .isTrue();

        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "F006",
                "\u4eca\u5929\u684c\u578b\u548c\u6392\u73ed\u600e\u4e48\u8c03\uff0c\u4e8c\u4eba\u684c\u56db\u4eba\u684c\u600e\u4e48\u5b89\u6392\uff1f",
                Collections.emptyMap()))
                .isFalse();

        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "DEMO_REST",
                "\u67e5\u8be2\u672c\u5468\u8425\u6536",
                Collections.emptyMap()))
                .isFalse();
        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "DEMO_REST",
                "\u4eca\u5929\u67e5\u8ba2\u5355",
                Collections.emptyMap()))
                .isFalse();
        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "DEMO_REST",
                "\u5ba2\u6237\u8bc4\u4ef7\u600e\u4e48\u6837",
                Collections.emptyMap()))
                .isFalse();
        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "DEMO_REST",
                "\u5dee\u8bc4\u5e94\u8be5\u600e\u4e48\u6539\u5584",
                Collections.emptyMap()))
                .isFalse();
        assertThat(orchestrator.shouldRouteRestaurantOwnerAction(
                "DEMO_REST",
                "\u4eca\u5929\u5546\u5708\u5ba2\u6d41\u753b\u50cf\u5bf9\u95e8\u5e97\u7ecf\u8425\u6709\u4ec0\u4e48\u5f71\u54cd\uff1f",
                Collections.emptyMap()))
                .isTrue();
    }

    @Test
    void acceptsRestaurantDemoContextSignalWhenFactoryIdWasRewritten() {
        assertThat(orchestrator.hasRestaurantOwnerActionSignal(
                "F_DEMO",
                Map.of("storeName", "\u9752\u82b1\u6912\u6f14\u793a\u5e97")))
                .isTrue();
    }
}
