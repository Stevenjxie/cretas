package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IntentExecutionOrchestrator — restaurant ops gold deterministic route")
class RestaurantOpsGoldRouteTest {

    private IntentExecutionOrchestrator orchestrator;
    private AIIntentService aiIntentService;
    private ToolRegistry toolRegistry;
    private QueryPreprocessorService queryPreprocessorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolRegistry = mock(ToolRegistry.class);
        queryPreprocessorService = mock(QueryPreprocessorService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                objectMapper,
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
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                queryPreprocessorService);
    }

    @Test
    @DisplayName("owner action execution delegates through governed restaurant owner advisor tool")
    void ownerActionExecutionUsesGovernedTool() throws Exception {
        ToolExecutor advisorTool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("restaurant_owner_action_advisor"))
                .thenReturn(Optional.of(advisorTool));
        when(advisorTool.execute(any(ToolCall.class), any()))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "success", true,
                        "data", Map.of(
                                "dataAvailable", true,
                                "source", "restaurant_owner_action_advisor",
                                "answer", "今天先让仓管补活鱼，厨师长盯出品，前台盯核销。",
                                "message", "今天先让仓管补活鱼，厨师长盯出品，前台盯核销。",
                                "sessionId", "owner-action-001",
                                "scenario", "operations_dispatch",
                                "suggestedFollowups", List.of(Map.of("question", "仓管具体做什么？"))
                        )
                )));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("这周营收同比上周怎么提高，仓管厨师长前台分别做什么？")
                .context(Map.of(
                        "ownerActionSessionId", "owner-action-001",
                        "ownerActionScenario", "operations_dispatch",
                        "storeName", "青花椒上海示范店",
                        "subSector", "中餐/川味酸菜鱼",
                        "period", "this_week"))
                .build();

        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "executeRestaurantOwnerActionChat",
                "DEMO_REST",
                request,
                7L);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMessage()).contains("仓管补活鱼");
        Map<?, ?> resultData = (Map<?, ?>) response.getResultData();
        assertThat(resultData.get("source")).isEqualTo("restaurant_owner_action");
        assertThat(resultData.get("advisorSource")).isEqualTo("restaurant_owner_action_advisor");
        assertThat((List<?>) resultData.get("suggestedFollowups"))
                .singleElement()
                .satisfies(followup -> {
                    Map<?, ?> item = (Map<?, ?>) followup;
                    assertThat(item.get("question")).isEqualTo("仓管具体做什么？");
                    assertThat(item.get("ownerActionScenario")).isEqualTo("operations_dispatch");
                });
        verify(toolRegistry).getExecutor("restaurant_owner_action_advisor");
        verify(advisorTool).execute(any(ToolCall.class), any());
    }

    @Test
    @DisplayName("owner action follow-up still routes through advisor when user negates an action")
    void ownerActionFollowUpWithNegatedActionUsesAdvisor() throws Exception {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_WRITE);

        ToolExecutor advisorTool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("restaurant_owner_action_advisor"))
                .thenReturn(Optional.of(advisorTool));
        when(advisorTool.execute(any(ToolCall.class), any()))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "success", true,
                        "data", Map.of(
                                "dataAvailable", true,
                                "source", "restaurant_owner_action_advisor",
                                "message", "今晚先把前厅加到18:00-20:00，厨房按招牌鱼备货。",
                                "answer", "今晚先把前厅加到18:00-20:00，厨房按招牌鱼备货。",
                                "sessionId", "owner-action-followup",
                                "scenario", "staffing_inventory"
                        )
                )));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("不要套餐，今天排班和备货怎么调？")
                .build();

        IntentExecuteResponse response = orchestrator.execute("DEMO_REST", request, 7L, "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OWNER_ACTION_CHAT");
        assertThat(response.getMessage()).contains("前厅").contains("备货");
        assertThat(((Map<?, ?>) response.getResultData()).get("source")).isEqualTo("restaurant_owner_action");
        verify(toolRegistry).getExecutor("restaurant_owner_action_advisor");
        verify(advisorTool).execute(any(ToolCall.class), any());
    }

    @Test
    @DisplayName("owner action business preference is not treated as cancel operation")
    void ownerActionNegatedBusinessPreferenceUsesAdvisor() throws Exception {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);

        ToolExecutor advisorTool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("restaurant_owner_action_advisor"))
                .thenReturn(Optional.of(advisorTool));
        when(advisorTool.execute(any(ToolCall.class), any()))
                .thenReturn(objectMapper.writeValueAsString(Map.of(
                        "success", true,
                        "data", Map.of(
                                "dataAvailable", true,
                                "source", "restaurant_owner_action_advisor",
                                "message", "不打折也能先做门口转化、套餐陈列和前厅话术。",
                                "answer", "不打折也能先做门口转化、套餐陈列和前厅话术。",
                                "sessionId", "owner-action-no-discount",
                                "scenario", "revenue_recovery"
                        )
                )));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("不想打折，那今天还有什么办法提升营收？")
                .build();

        IntentExecuteResponse response = orchestrator.execute("DEMO_REST", request, 7L, "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OWNER_ACTION_CHAT");
        assertThat(response.getMessage()).contains("不打折").contains("转化");
        verify(toolRegistry).getExecutor("restaurant_owner_action_advisor");
        verify(advisorTool).execute(any(ToolCall.class), any());
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
        assertThat(orchestrator.matchRestaurantOpsIntent("查询本周营收", "RESTAURANT"))
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
    @DisplayName("routes store benchmark decision questions to owner action before weekday report")
    void routesStoreBenchmarkDecisionToOwnerAction() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");

        for (String question : new String[]{
                "如果日均不差但工作日弱，应该复制哪家店做法？",
                "这家店和同商圈门店比，问题在客流还是执行？",
                "连锁内部排名不高，今天先补哪个动作？",
                "哪些菜值得主推，哪些低价值菜要排除？",
                "主推单品怎么判断有没有拉动加购？"
        }) {
            Boolean shouldRoute = ReflectionTestUtils.invokeMethod(
                    orchestrator,
                    "shouldRouteRestaurantOwnerAction",
                    "DEMO_REST",
                    question,
                    Map.of());

            assertThat(shouldRoute).as(question).isTrue();
        }
    }

    @Test
    @DisplayName("routes role, menu pairing, and kitchen action questions to owner action before report tools")
    void routesBossDecisionVariantsToOwnerAction() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");

        for (String question : new String[]{
                "厨师长、仓管、前台今天分别盯什么？",
                "酸菜鱼配什么小菜饮品更合理，别只看销量",
                "如果今晚客流比昨天多，厨房备菜怎么调？",
                "商场今天有活动的话，我们门口和套餐怎么配合？",
                "厨房慢和服务慢哪个先处理？",
        }) {
            Boolean shouldRoute = ReflectionTestUtils.invokeMethod(
                    orchestrator,
                    "shouldRouteRestaurantOwnerAction",
                    "DEMO_REST",
                    question,
                    Map.of());

            assertThat(shouldRoute).as(question).isTrue();
        }
    }

    @Test
    @DisplayName("owner action route refuses manufacturing factories even with owner context")
    void ownerActionRouteRefusesFactoryDomainEvenWithContext() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("F006")).thenReturn("FACTORY");

        Boolean directQuestion = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "shouldRouteRestaurantOwnerAction",
                "F006",
                "老板今天应该怎么提高营收？",
                Map.of());
        Boolean contextualFollowUp = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "shouldRouteRestaurantOwnerAction",
                "F006",
                "具体怎么执行？",
                Map.of("ownerActionSessionId", "owner-action-leaked"));

        assertThat(directQuestion).isFalse();
        assertThat(contextualFollowUp).isFalse();
    }

    @Test
    @DisplayName("owner action route accepts restaurant follow-up only when restaurant domain is confirmed")
    void ownerActionRouteAcceptsRestaurantContextOnlyForRestaurantDomain() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("RES_3101_009")).thenReturn("RESTAURANT");

        Boolean shouldRoute = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "shouldRouteRestaurantOwnerAction",
                "RES_3101_009",
                "具体怎么执行？",
                Map.of("ownerActionSessionId", "owner-action-session-1"));

        assertThat(shouldRoute).isTrue();
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
                .thenReturn(Optional.empty());
        when(aiIntentService.getIntentByCode(eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.of(salesSummary));

        IntentMatchResult result = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "tryOrchestratorPhraseShortcut",
                "总营收和客单价表现怎么样",
                "DEMO_REST");

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch().getIntentCode()).isEqualTo("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(result.getBestMatch().getToolName()).isEqualTo("restaurant_ops_gold_analysis");

        IntentMatchResult restaurantOpsResult = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "tryRestaurantOpsPhraseShortcut",
                "查询本周营收",
                "DEMO_REST");

        assertThat(restaurantOpsResult).isNotNull();
        assertThat(restaurantOpsResult.getBestMatch().getIntentCode()).isEqualTo("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(restaurantOpsResult.getBestMatch().getToolName()).isEqualTo("restaurant_ops_gold_analysis");
    }
}
