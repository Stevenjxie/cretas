package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.restaurant.TieredIntentDelegate;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
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
import com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IntentExecutionOrchestrator — restaurant ops gold deterministic route")
class RestaurantOpsGoldRouteTest {

    private IntentExecutionOrchestrator orchestrator;
    private AIIntentService aiIntentService;
    private ToolRegistry toolRegistry;
    private ToolDispatchService toolDispatchService;
    private BusinessTypeGate businessTypeGate;
    private WriteGuardService writeGuardService;
    private QueryPreprocessorService queryPreprocessorService;
    private ToolExecutionGateway toolExecutionGateway;
    private AuthenticatedToolPrincipalFactory principalFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("conceptual internal-external and missing-dimension wording uses comprehensive synthesis")
    void conceptualComprehensiveWordingDoesNotFallIntoOwnerActionShortcuts() {
        assertThat(orchestrator.isRestaurantComprehensiveSynthesisQuestion(
                "最近30天全部门店最值得优先解决的经营问题是什么？"
                        + "把内部经营和外部环境维度一起看，缺数据就告诉我还需补什么，不要猜数字"))
                .isTrue();
        assertThat(orchestrator.isRestaurantComprehensiveSynthesisQuestion(
                "不要只把优化理解成滞销。请按最近30天全部门店的销量、销售额"
                        + "和其他可用菜品经营指标给出优化候选，并明确缺失维度"))
                .isTrue();
        assertThat(orchestrator.isRestaurantComprehensiveSynthesisQuestion(
                "本月全部门店招牌菜营业额是多少"))
                .isFalse();
    }

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolRegistry = mock(ToolRegistry.class);
        toolDispatchService = mock(ToolDispatchService.class);
        businessTypeGate = mock(BusinessTypeGate.class);
        writeGuardService = mock(WriteGuardService.class);
        queryPreprocessorService = mock(QueryPreprocessorService.class);
        toolExecutionGateway = mock(ToolExecutionGateway.class);
        principalFactory = mock(AuthenticatedToolPrincipalFactory.class);
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
                toolDispatchService,
                mock(DynamicToolSelectionService.class),
                queryPreprocessorService);
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", writeGuardService);
        ReflectionTestUtils.setField(orchestrator, "toolExecutionGateway", toolExecutionGateway);
        ReflectionTestUtils.setField(
                orchestrator, "authenticatedToolPrincipalFactory", principalFactory);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        when(writeGuardService.isWriteIntent(any())).thenReturn(false);
        when(principalFactory.create(anyString(), anyLong(), anyString()))
                .thenReturn(principal());
    }

    @Test
    @DisplayName("owner action execution delegates through governed restaurant owner advisor tool")
    void ownerActionExecutionUsesGovernedTool() throws Exception {
        Instant before = Instant.now();
        stubOwnerGateway(Map.of(
                "dataAvailable", true,
                "source", "restaurant_owner_action_advisor",
                "answer", "今天先让仓管补活鱼，厨师长盯出品，前台盯核销。",
                "message", "今天先让仓管补活鱼，厨师长盯出品，前台盯核销。",
                "sessionId", "owner-action-001",
                "scenario", "operations_dispatch",
                "suggestedFollowups", List.of(Map.of("question", "仓管具体做什么？"))));

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
                7L,
                "restaurant_owner");

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
        ArgumentCaptor<ToolExecutionCommand> command =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(toolExecutionGateway).execute(command.capture());
        assertThat(command.getValue().toolName()).isEqualTo("restaurant_owner_action_advisor");
        assertThat(command.getValue().expectedDescriptorVersion()).isEqualTo("2.0.0");
        assertThat(command.getValue().source()).isEqualTo(ToolExecutionSource.AI_CHAT);
        assertThat(command.getValue().mode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(command.getValue().idempotencyKey()).isEmpty();
        assertThat(command.getValue().confirmationProof()).isEmpty();
        assertThat(command.getValue().approvalProof()).isEmpty();
        assertThat(command.getValue().deadline()).isAfter(before);
        assertThat(command.getValue().deadline()).isBeforeOrEqualTo(before.plusSeconds(31));
        assertThat(command.getValue().requestId()).isEqualTo(command.getValue().correlationId());
        verify(principalFactory).create("DEMO_REST", 7L, "restaurant_owner");
    }

    @Test
    @DisplayName("pending named-dish clarification beats generic owner action")
    void pendingNamedDishClarificationBeatsOwnerAction() {
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);
        when(delegate.tryDelegate(
                eq("DEMO_REST"),
                any(),
                any(),
                eq("orchestrator_null_intent")))
                .thenReturn(Map.of(
                        "message", "你想看哪个时间范围？",
                        "clarificationContinuation", true,
                        "suggestedFollowups", List.of(
                                Map.of("label", "本月", "question", "本月"))));

        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "executeRestaurantOwnerActionChat",
                "DEMO_REST",
                IntentExecuteRequest.builder()
                        .userInput("怎么优化它")
                        .sessionId("named-dish-pending-time")
                        .build(),
                7L,
                "restaurant_owner");

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("你想看哪个时间范围？");
        Map<?, ?> resultData = (Map<?, ?>) response.getResultData();
        assertThat(resultData.get("clarificationContinuation")).isEqualTo(true);
        assertThat(resultData.get("suggestedFollowups"))
                .isEqualTo(List.of(Map.of("label", "本月", "question", "本月")));
        verify(toolExecutionGateway, never()).execute(any(ToolExecutionCommand.class));
    }

    @Test
    @DisplayName("store-scoped named-dish optimization beats generic owner action")
    void storeScopedNamedDishOptimizationBeatsOwnerAction() {
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);
        when(delegate.tryDelegate(
                eq("DEMO_REST"),
                any(),
                any(),
                eq("orchestrator_null_intent")))
                .thenReturn(Map.of(
                        "code", "RESTAURANT_OPS_STORE_MARGIN",
                        "message", "门店范围：青花椒南方百联店；娃娃菜销量优化建议。",
                        "conversationContext", Map.of(
                                "focus_entity", Map.of(
                                        "type", "dish",
                                        "name", "娃娃菜"),
                                "requested_metrics", List.of("sales_volume"),
                                "analysis_action", "optimize",
                                "store_scope", "single",
                                "store_names", List.of("青花椒南方百联店"))));

        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "executeRestaurantOwnerActionChat",
                "DEMO_REST",
                IntentExecuteRequest.builder()
                        .userInput("怎么优化它")
                        .sessionId("named-dish-store-optimization")
                        .build(),
                7L,
                "restaurant_owner");

        assertThat(response).isNotNull();
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_STORE_MARGIN");
        assertThat(response.getMessage()).contains("娃娃菜销量优化");
        verify(toolExecutionGateway, never()).execute(any(ToolExecutionCommand.class));
    }

    @Test
    void ownerActionRejectsDeniedFailedAndMalformedGatewayResults() {
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("今天老板先做什么？")
                .build();

        for (ToolExecutionStatus status : List.of(
                ToolExecutionStatus.DENIED, ToolExecutionStatus.FAILED)) {
            org.mockito.Mockito.reset(toolExecutionGateway);
            when(toolExecutionGateway.execute(any(ToolExecutionCommand.class)))
                    .thenAnswer(invocation -> gatewayResult(
                            invocation.getArgument(0),
                            status,
                            objectMapper.valueToTree(Map.of(
                                    "success", false,
                                    "error", "sensitive downstream detail"))));
            IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                    orchestrator,
                    "executeRestaurantOwnerActionChat",
                    "DEMO_REST",
                    request,
                    7L,
                    "restaurant_owner");
            assertThat(response.getStatus()).isEqualTo("ERROR");
            assertThat(response.getMessage()).contains("暂时不可用")
                    .doesNotContain("sensitive downstream detail");
        }

        org.mockito.Mockito.reset(toolExecutionGateway);
        when(toolExecutionGateway.execute(any(ToolExecutionCommand.class)))
                .thenAnswer(invocation -> gatewayResult(
                        invocation.getArgument(0),
                        ToolExecutionStatus.SUCCEEDED,
                        objectMapper.valueToTree(Map.of(
                                "success", true,
                                "data", List.of("wrong-shape")))));
        IntentExecuteResponse malformed = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "executeRestaurantOwnerActionChat",
                "DEMO_REST",
                request,
                7L,
                "restaurant_owner");
        assertThat(malformed.getStatus()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("owner action unavailable data is an explicit error, never fake success")
    void ownerActionUnavailableDataReturnsErrorStatus() throws Exception {
        stubOwnerGateway(Map.of(
                "dataAvailable", false,
                "message", "sensitive downstream detail",
                "answer", "sensitive downstream detail"));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("今天老板先做什么？")
                .build();

        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "executeRestaurantOwnerActionChat",
                "DEMO_REST",
                request,
                7L,
                "restaurant_owner");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("暂时不可用")
                .doesNotContain("sensitive downstream detail");
        assertThat(response.getResultData()).isNull();
    }

    @Test
    @DisplayName("owner action follow-up still routes through advisor when user negates an action")
    void ownerActionFollowUpWithNegatedActionUsesAdvisor() throws Exception {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_WRITE);

        stubOwnerGateway(Map.of(
                "dataAvailable", true,
                "source", "restaurant_owner_action_advisor",
                "message", "今晚先把前厅加到18:00-20:00，厨房按招牌鱼备货。",
                "answer", "今晚先把前厅加到18:00-20:00，厨房按招牌鱼备货。",
                "sessionId", "owner-action-followup",
                "scenario", "staffing_inventory"));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("不要套餐，今天排班和备货怎么调？")
                .build();

        IntentExecuteResponse response = orchestrator.execute("DEMO_REST", request, 7L, "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OWNER_ACTION_CHAT");
        assertThat(response.getMessage()).contains("前厅").contains("备货");
        assertThat(((Map<?, ?>) response.getResultData()).get("source")).isEqualTo("restaurant_owner_action");
        verify(toolExecutionGateway, atLeastOnce()).execute(any(ToolExecutionCommand.class));
    }

    @Test
    @DisplayName("owner action business preference is not treated as cancel operation")
    void ownerActionNegatedBusinessPreferenceUsesAdvisor() throws Exception {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);

        stubOwnerGateway(Map.of(
                "dataAvailable", true,
                "source", "restaurant_owner_action_advisor",
                "message", "不打折也能先做门口转化、套餐陈列和前厅话术。",
                "answer", "不打折也能先做门口转化、套餐陈列和前厅话术。",
                "sessionId", "owner-action-no-discount",
                "scenario", "revenue_recovery"));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("不想打折，那今天还有什么办法提升营收？")
                .build();

        IntentExecuteResponse response = orchestrator.execute("DEMO_REST", request, 7L, "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OWNER_ACTION_CHAT");
        assertThat(response.getMessage()).contains("不打折").contains("转化");
        verify(toolExecutionGateway, atLeastOnce()).execute(any(ToolExecutionCommand.class));
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
        assertThat(orchestrator.matchRestaurantOpsIntent("按月份绘制整体毛利率趋势曲线", "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("在整体毛利率趋势图中添加70%计划线和60%预警线", "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("整体毛利率是多少", "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("上月净利率是多少", "RESTAURANT"))
                .contains("INCOME_STATEMENT_QUERY");
        assertThat(orchestrator.matchRestaurantOpsIntent("整体净利率", "RESTAURANT"))
                .contains("INCOME_STATEMENT_QUERY");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪家店最赚钱，需要复盘哪家店", "RESTAURANT"))
                .contains("RESTAURANT_OPS_STORE_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪家门店净赚最多", "RESTAURANT"))
                .contains("RESTAURANT_OPS_STORE_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("总营收和客单价表现怎么样", "RESTAURANT"))
                .contains("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(orchestrator.matchRestaurantOpsIntent(
                "分析哪些菜品可以从菜单中被优化。优化不只指慢销，请综合销量、销售额、毛利、退菜、差评、制作时长和损耗；缺少哪些数据也要逐项说明。",
                "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("分析本周菜品销售额", "RESTAURANT"))
                .contains("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(orchestrator.matchRestaurantOpsIntent("查询本周营收", "RESTAURANT"))
                .contains("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(orchestrator.matchRestaurantOpsIntent("昨天的营业额是高于前天还是低于前天？", "RESTAURANT"))
                .contains("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(orchestrator.matchRestaurantOpsIntent(
                "最近7天晚市出餐慢，是订单集中、人员不足还是工序瓶颈？请分别用数据判断。",
                "RESTAURANT"))
                .contains("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(orchestrator.matchRestaurantOpsIntent("成本毛利先查哪几项？", "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("要提升毛利率，哪些事情今天先不要做？", "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent(
                "那毛利呢？请沿用刚才比较的两个日期。",
                "RESTAURANT"))
                .contains("RESTAURANT_OPS_GROSS_MARGIN");
        assertThat(orchestrator.matchRestaurantOpsIntent("周末周中营业额对比如何", "RESTAURANT"))
                .contains("RESTAURANT_WEEKDAY_WEEKEND");
        assertThat(orchestrator.matchRestaurantOpsIntent("上个月哪家门店营收最高？", "RESTAURANT"))
                .contains("RESTAURANT_STORE_REVENUE_RANK");
        assertThat(orchestrator.matchRestaurantOpsIntent("本月哪个店营业额最高", "RESTAURANT"))
                .contains("RESTAURANT_STORE_REVENUE_RANK");
        assertThat(orchestrator.matchRestaurantOpsIntent("2026年6月销售额冠军是哪家分店", "RESTAURANT"))
                .contains("RESTAURANT_STORE_REVENUE_RANK");
        assertThat(orchestrator.matchRestaurantOpsIntent("上个月各门店营收排名", "RESTAURANT"))
                .contains("RESTAURANT_STORE_REVENUE_RANK");
        assertThat(orchestrator.matchRestaurantOpsIntent("上个月营收最高的门店是哪家", "RESTAURANT"))
                .contains("RESTAURANT_STORE_REVENUE_RANK");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪个月营收最高，为什么", "RESTAURANT"))
                .contains("RESTAURANT_PEAK_MONTH");
        assertThat(orchestrator.matchRestaurantOpsIntent("营收最高的是哪个月份", "RESTAURANT"))
                .contains("RESTAURANT_PEAK_MONTH");
        assertThat(orchestrator.matchRestaurantOpsIntent(
                "对比峰值月和次高月的订单量与客单价", "RESTAURANT"))
                .contains("RESTAURANT_PEAK_MONTH");
        assertThat(orchestrator.matchRestaurantOpsIntent(
                "比较营收峰值月与第二高月份的单量、平均客单", "RESTAURANT"))
                .contains("RESTAURANT_PEAK_MONTH");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪个月门店营收最高", "RESTAURANT"))
                .contains("RESTAURANT_PEAK_MONTH");
        assertThat(orchestrator.matchRestaurantOpsIntent("哪个门店哪个月营收最高", "RESTAURANT"))
                .isEmpty();
        assertThat(orchestrator.matchRestaurantOpsIntent("上个月哪家门店营收最高，为什么", "RESTAURANT"))
                .isEmpty();
        assertThat(orchestrator.matchRestaurantOpsIntent("上个月哪家门店毛利最高", "RESTAURANT"))
                .contains("RESTAURANT_OPS_STORE_MARGIN");
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
                "主推单品怎么判断有没有拉动加购？",
                "本周营业额下降，仓管厨师长前台分别要做什么？",
                "这家店不是最差但客单价不高，区域经理今天看什么？",
                "那就按第2种，指定门店下滑继续分析",
                "现在有哪些动作先别做？",
                "告诉我哪些经营措施暂时不要做"
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
    @DisplayName("explicit restaurant facts are never stolen by owner action keywords")
    void explicitRestaurantFactsBypassOwnerAction() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");

        for (String question : new String[]{
                "昨天与前天全部门店营业额分别是多少？请给差额和升降结论",
                "最近30天全部门店毛利和营业额分别是多少，并展示计算口径",
                "最近7天青花椒南方百联店和青花椒徐汇光启城店的招牌青花椒味(单人份)成本和毛利分别是多少",
                "最近30天全部门店的净利润和翻台率是多少？缺数据不要猜"
        }) {
            Boolean shouldRoute = ReflectionTestUtils.invokeMethod(
                    orchestrator,
                    "shouldRouteRestaurantOwnerAction",
                    "DEMO_REST",
                    question,
                    Map.of());

            assertThat(shouldRoute).as(question).isFalse();
        }
    }

    @Test
    @DisplayName("metric optimisation follow-ups stay on typed restaurant analysis context")
    void metricOptimizationFollowUpsBypassOwnerAction() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");

        for (String question : new String[]{
                "销量怎么优化",
                "第一名的毛利如何提升",
                "这个菜的成本怎么改善"
        }) {
            Boolean shouldRoute = ReflectionTestUtils.invokeMethod(
                    orchestrator,
                    "shouldRouteRestaurantOwnerAction",
                    "DEMO_REST",
                    question,
                    Map.of("ownerActionSessionId", "stale-owner-context"));

            assertThat(shouldRoute).as(question).isFalse();
        }

        Boolean realOwnerPlan = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "shouldRouteRestaurantOwnerAction",
                "DEMO_REST",
                "本周营业额下降，仓管、厨师长和前台分别要做什么？",
                Map.of());
        assertThat(realOwnerPlan).isTrue();
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

    @Test
    @DisplayName("explicit restaurant report execution falls back to platform intent config")
    void explicitRestaurantReportExecutionUsesPlatformFallback() {
        AIIntentConfig salesSummary = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                .intentName("Restaurant sales summary")
                .intentCategory("SMARTBI")
                .toolName("restaurant_ops_gold_analysis")
                .businessType("RESTAURANT")
                .build();
        when(aiIntentService.getIntentByCode(eq("DEMO_REST"), eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.empty());
        when(aiIntentService.getIntentByCode(eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.of(salesSummary));
        when(aiIntentService.hasPermission(eq("RESTAURANT_OPS_SALES_SUMMARY"), eq("admin")))
                .thenReturn(true);

        ToolExecutor goldTool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("restaurant_ops_gold_analysis"))
                .thenReturn(Optional.of(goldTool));
        when(toolDispatchService.executeWithTool(
                eq(goldTool),
                eq("DEMO_REST"),
                any(IntentExecuteRequest.class),
                eq(salesSummary),
                eq(7L),
                eq("admin"),
                any()))
                .thenReturn(IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                        .intentName("Restaurant sales summary")
                        .status("SUCCESS")
                        .message("本周营收已按餐饮报表汇总")
                        .resultData(Map.of("source", "restaurant_ops_gold_analysis"))
                        .build());

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("查询本周营收")
                .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                .build();

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent("DEMO_REST", request, 7L, "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(response.getMessage()).contains("餐饮报表");
        verify(toolDispatchService).executeWithTool(
                eq(goldTool),
                eq("DEMO_REST"),
                any(IntentExecuteRequest.class),
                eq(salesSummary),
                eq(7L),
                eq("admin"),
                any());
    }

    @Test
    @DisplayName("tiered-first restaurant phrase reaches semantic planner before deterministic shortcut")
    void tieredFirstRestaurantPhraseUsesSemanticPlanner() {
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        ReflectionTestUtils.setField(orchestrator, "tieredFirstEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);
        List<Map<String, Object>> followups = List.of(Map.of(
                "label", "看菜品成本",
                "question", "招牌菜的成本如何？"));
        when(delegate.tryDelegate(
                eq("DEMO_REST"),
                any(),
                any(),
                eq("orchestrator_null_intent")))
                .thenReturn(Map.of(
                        "message", "招牌菜销量第一",
                        "code", "RESTAURANT_OPS_GROSS_MARGIN",
                        "charts", List.of(),
                        "kpis", List.of(),
                        "warning", "咨询模式只展示分析结果，没有执行下架操作。",
                        "contractPass", true,
                        "queryPlanHash", "plan-42",
                        "executedResolvers", List.of("RESTAURANT_OPS_GROSS_MARGIN"),
                        "suggestedFollowups", followups));

        IntentExecuteResponse response = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder().userInput("哪个菜卖得好").build(),
                7L,
                "admin");

        assertThat(response.getMessage()).isEqualTo("招牌菜销量第一");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_GROSS_MARGIN");
        Map<?, ?> resultData = (Map<?, ?>) response.getResultData();
        assertThat(resultData.get("queryPlanHash")).isEqualTo("plan-42");
        assertThat(resultData.get("executedResolvers"))
                .isEqualTo(List.of("RESTAURANT_OPS_GROSS_MARGIN"));
        assertThat(resultData.get("suggestedFollowups")).isEqualTo(followups);
        assertThat(resultData.get("warning"))
                .isEqualTo("咨询模式只展示分析结果，没有执行下架操作。");
        verify(toolDispatchService, never()).executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("restaurant mutation verbs stay on governed write route, while history reads stay semantic")
    void restaurantMutationVocabularyDoesNotFallIntoReadOnlySemanticPlanner() {
        for (String query : new String[]{
                "下架最近7天销量最低的5道菜",
                "把卤炸牛肉串停售",
                "给招牌菜调价",
                "创建一个满减活动",
                "给本月复购客户发券"
        }) {
            assertThat(IntentExecutionOrchestrator.isRestaurantWriteRequest(query))
                    .as(query)
                    .isTrue();
        }

        for (String query : new String[]{
                "看看已下架菜品",
                "查询下架记录",
                "分析调价历史"
        }) {
            assertThat(IntentExecutionOrchestrator.isRestaurantWriteRequest(query))
                    .as(query)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("tiered-first semantic planner precedes comprehensive and owner-action heuristics")
    void semanticPlannerPrecedesLegacyRestaurantHeuristics() {
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        RestaurantGrossMarginChatRouteSelector boundedSelector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        ReflectionTestUtils.setField(orchestrator, "tieredFirstEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);
        ReflectionTestUtils.setField(
                orchestrator, "restaurantGrossMarginChatRouteSelector", boundedSelector);
        when(delegate.tryDelegate(
                eq("DEMO_REST"),
                any(),
                any(),
                eq("orchestrator_null_intent")))
                .thenReturn(Map.of(
                        "message", "已按可用经营数据完成诊断并给出提升方案。",
                        "code", "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
                        "charts", List.of(),
                        "kpis", List.of(),
                        "contractPass", true,
                        "queryPlanHash", "plan-llm-first"));

        for (String query : new String[]{
                "请综合分析最近30天全部门店经营情况，结合客流、菜品销量、毛利、周边竞争、天气、活动、评价和排班给出建议",
                "这周营收怎么提高",
                "本月毛利下降，仓管、厨师长和前台分别要做什么"
        }) {
            IntentExecuteResponse response = orchestrator.execute(
                    "DEMO_REST",
                    IntentExecuteRequest.builder().userInput(query).mode("READ").build(),
                    7L,
                    "admin");

            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getIntentCode())
                    .isEqualTo("RESTAURANT_OPS_BUSINESS_OPTIMIZATION");
            assertThat(response.getMessage()).contains("诊断").contains("提升方案");
        }

        verify(boundedSelector, never()).select(anyString(), anyString(), anyString());
        verify(delegate, times(3)).tryDelegate(
                eq("DEMO_REST"),
                any(),
                any(),
                eq("orchestrator_null_intent"));
        verify(toolDispatchService, never()).executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("explicit read veto stays ahead of both restaurant semantic runtimes")
    void explicitReadVetoCannotBeExecutedBySemanticPlannerOrBoundedRuntime() {
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        RestaurantGrossMarginChatRouteSelector boundedSelector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        ReflectionTestUtils.setField(orchestrator, "tieredFirstEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);
        ReflectionTestUtils.setField(
                orchestrator, "restaurantGrossMarginChatRouteSelector", boundedSelector);
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);

        IntentExecuteResponse response = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder().userInput("别看订单").mode("READ").build(),
                7L,
                "admin");

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getIntentRecognized()).isFalse();
        assertThat(response.getMessage()).contains("取消").contains("查询或处理什么");
        verify(delegate, never()).tryDelegate(anyString(), any(), any(), anyString());
        verify(boundedSelector, never()).select(anyString(), anyString(), anyString());
        verify(toolDispatchService, never()).executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("restaurant business domain enables semantic planner without an ID prefix")
    void restaurantDomainEnablesTieredFirstForLegacyFactoryId() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        ReflectionTestUtils.setField(orchestrator, "tieredFirstEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);
        when(configService.resolveBusinessDomain("QHJ01")).thenReturn("RESTAURANT");
        when(delegate.tryDelegate(
                eq("QHJ01"),
                any(),
                any(),
                eq("orchestrator_null_intent")))
                .thenReturn(Map.of(
                        "message", "招牌菜销量第一",
                        "code", "RESTAURANT_OPS_GROSS_MARGIN",
                        "charts", List.of(),
                        "kpis", List.of(),
                        "contractPass", true,
                        "queryPlanHash", "plan-domain"));

        IntentExecuteResponse response = orchestrator.execute(
                "QHJ01",
                IntentExecuteRequest.builder().userInput("哪个菜卖得好").build(),
                7L,
                "admin");

        assertThat(response.getMessage()).isEqualTo("招牌菜销量第一");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_GROSS_MARGIN");
        verify(toolDispatchService, never()).executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("natural restaurant revenue question routes through gold report intent")
    void naturalRestaurantRevenueQuestionRoutesThroughGoldReportIntent() {
        AIIntentConfig salesSummary = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                .intentName("Restaurant sales summary")
                .intentCategory("SMARTBI")
                .toolName("restaurant_ops_gold_analysis")
                .businessType("RESTAURANT")
                .build();
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.NONE);
        when(aiIntentService.getIntentByCode(eq("DEMO_REST"), eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.empty());
        when(aiIntentService.getIntentByCode(eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.of(salesSummary));
        when(aiIntentService.hasPermission(eq("RESTAURANT_OPS_SALES_SUMMARY"), eq("admin")))
                .thenReturn(true);

        ToolExecutor goldTool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("restaurant_ops_gold_analysis"))
                .thenReturn(Optional.of(goldTool));
        when(toolDispatchService.executeWithTool(
                eq(goldTool),
                eq("DEMO_REST"),
                any(IntentExecuteRequest.class),
                eq(salesSummary),
                eq(7L),
                eq("admin"),
                any()))
                .thenReturn(IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                        .intentName("Restaurant sales summary")
                        .status("SUCCESS")
                        .message("本周营收已按餐饮报表汇总")
                        .resultData(Map.of("source", "restaurant_ops_gold_analysis"))
                        .build());

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("查询本周营收")
                .build();

        IntentExecuteResponse response = orchestrator.execute("DEMO_REST", request, 7L, "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_SALES_SUMMARY");
        assertThat(response.getMessage()).contains("餐饮报表");
        verify(toolDispatchService).executeWithTool(
                eq(goldTool),
                eq("DEMO_REST"),
                any(IntentExecuteRequest.class),
                eq(salesSummary),
                eq(7L),
                eq("admin"),
                any());
    }

    @Test
    @DisplayName("positive restaurant report phrase routes before preprocessor veto noise")
    void positiveRestaurantReportPhraseRoutesBeforePreprocessorVetoNoise() {
        AIIntentConfig salesSummary = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                .intentName("Restaurant sales summary")
                .intentCategory("SMARTBI")
                .toolName("restaurant_ops_gold_analysis")
                .businessType("RESTAURANT")
                .build();
        when(queryPreprocessorService.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_WRITE);
        when(aiIntentService.getIntentByCode(eq("DEMO_REST"), eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.empty());
        when(aiIntentService.getIntentByCode(eq("RESTAURANT_OPS_SALES_SUMMARY")))
                .thenReturn(Optional.of(salesSummary));
        when(aiIntentService.hasPermission(eq("RESTAURANT_OPS_SALES_SUMMARY"), eq("admin")))
                .thenReturn(true);

        ToolExecutor goldTool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("restaurant_ops_gold_analysis"))
                .thenReturn(Optional.of(goldTool));
        when(toolDispatchService.executeWithTool(
                eq(goldTool),
                eq("DEMO_REST"),
                any(IntentExecuteRequest.class),
                eq(salesSummary),
                eq(7L),
                eq("admin"),
                any()))
                .thenReturn(IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode("RESTAURANT_OPS_SALES_SUMMARY")
                        .intentName("Restaurant sales summary")
                        .status("SUCCESS")
                        .message("本周营收已按餐饮报表汇总")
                        .resultData(Map.of("source", "restaurant_ops_gold_analysis"))
                        .build());

        IntentExecuteResponse response = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder().userInput("查询本周营收").build(),
                7L,
                "admin");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_SALES_SUMMARY");
        verify(toolDispatchService).executeWithTool(
                eq(goldTool),
                eq("DEMO_REST"),
                any(IntentExecuteRequest.class),
                eq(salesSummary),
                eq(7L),
                eq("admin"),
                any());
    }

    @Test
    @DisplayName("today revenue and margin request stays on analytical read route")
    void todayRevenueAndMarginQuestionIsAnalyticalRead() {
        Boolean analytical = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "isRestaurantAnalyticalReadQuestion",
                "请给出今天的整体经营情况：营业额、毛利额、毛利率；如果今天没有数据就明确说明");

        assertThat(analytical).isTrue();
    }

    @Test
    @DisplayName("ambiguous turnover metric asks for clarification without revenue substitution")
    void ambiguousTurnoverMetricGetsDeterministicClarification() {
        String query = "最近翻台拉胯，先判断我说的是翻台率还是翻台次数；如果无法确定先澄清，不要直接拿营业额替代";

        assertThat(orchestrator.isAmbiguousRestaurantTurnoverMetricQuestion(query)).isTrue();
        IntentExecuteResponse response = orchestrator.buildRestaurantTurnoverMetricClarificationResponse(
                IntentExecuteRequest.builder().userInput(query).sessionId("turnover-1").build());

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage())
                .contains("翻台率")
                .contains("翻台次数")
                .contains("不会拿营业额替代")
                .contains("开台和结账记录");
    }

    @Test
    @DisplayName("store net profit never falls through to revenue or gross margin substitution")
    void storeNetProfitGetsHonestCapabilityGap() {
        String query = "请给出昨天和前天各门店净利润，不要用营业额或毛利替代";

        assertThat(orchestrator.isUnsupportedRestaurantStoreNetProfitQuestion(query)).isTrue();
        IntentExecuteResponse response = orchestrator.buildRestaurantStoreNetProfitGapResponse(
                IntentExecuteRequest.builder().userInput(query).sessionId("net-profit-1").build());

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage())
                .contains("昨天")
                .contains("前天")
                .contains("按门店归集的费用、税费及其他收支")
                .contains("不能可靠计算各门店净利润")
                .contains("不会用营业额或毛利替代")
                .contains("已覆盖销售的毛利");

        Boolean analytical = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "isRestaurantAnalyticalReadQuestion",
                query);
        assertThat(analytical).isTrue();
    }

    @Test
    @DisplayName("store pronoun bypasses both early restaurant phrase shortcuts")
    void storePronounRequiresContextResolutionBeforeShortcut() {
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForStoreReference(
                "那它的毛利率也是第一吗？请沿用刚才的门店和日期范围")).isTrue();
    }

    @Test
    @DisplayName("price elasticity request returns explicit capability gap and missing fields")
    void priceElasticityGetsHonestCapabilityGap() {
        String query = "请给出菜品价格弹性、95%置信区间和因果效果；缺字段就明确说明";

        assertThat(orchestrator.isUnsupportedRestaurantPriceElasticityQuestion(query)).isTrue();
        IntentExecuteResponse response = orchestrator.buildRestaurantPriceElasticityGapResponse(
                IntentExecuteRequest.builder().userInput(query).sessionId("elasticity-1").build());

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage())
                .contains("不能可靠计算价格弹性")
                .contains("95%置信区间")
                .contains("多次真实价格变动")
                .contains("销量、订单量和曝光")
                .contains("促销、折扣、门店和日期")
                .contains("控制因素")
                .doesNotContain("已计算");
    }

    @Test
    @DisplayName("cost-margin planning explains objective and a deterministic check order")
    void costMarginPlanningReturnsOrderedChecks() {
        IntentExecuteResponse response = orchestrator.buildRestaurantCostMarginCheckOrderResponse(
                IntentExecuteRequest.builder().userInput("成本毛利先查哪几项？").build());

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getMessage())
                .contains("我理解的目标")
                .contains("1. 先看收入、订单和折扣")
                .contains("2. 再核对配方用量和最新进价")
                .contains("3. 再看领料、报损和盘点差异")
                .contains("4. 最后按门店和菜品")
                .contains("明确标出缺口")
                .doesNotContain("请选择");
    }

    @Test
    @DisplayName("margin safety answer gives premise risk and minimum validation without invented benefit")
    void prohibitedMarginActionsReturnBoundedAdvice() {
        IntentExecuteResponse response = orchestrator.buildRestaurantMarginProhibitedActionsResponse(
                IntentExecuteRequest.builder()
                        .userInput("要提升毛利率，哪些事情今天先不要做？")
                        .build());

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getMessage())
                .contains("今天先不要做")
                .contains("前提")
                .contains("风险")
                .contains("最小验证")
                .contains("不会凭空估算收益金额")
                .doesNotContain("预计提升")
                .doesNotContain("预计避免");
    }

    @Test
    @DisplayName("tiered-first planner miss fails closed before legacy context rerouting")
    void tieredFirstPlannerMissFailsClosed() {
        TieredIntentDelegate delegate = mock(TieredIntentDelegate.class);
        ReflectionTestUtils.setField(orchestrator, "tieredFirstEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);

        IntentExecuteResponse response = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder()
                        .userInput("那它的毛利率也是第一吗？")
                        .sessionId("session-typed-context")
                        .build(),
                7L,
                "admin");

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage()).contains("没有执行任何分析");
        verify(toolDispatchService, never()).executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any());
    }


    @Test
    @DisplayName("restaurant contract answers execute before generic recognition and tool fallback")
    void deterministicRestaurantContractsRunAtExecuteEntry() {
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
        when(configService.resolveBusinessDomain("DEMO_REST")).thenReturn("RESTAURANT");

        IntentExecuteResponse turnover = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder()
                        .userInput("最近翻台拉胯，先判断是翻台率还是翻台次数，不要拿营业额替代")
                        .build(),
                7L,
                "admin");
        IntentExecuteResponse netProfit = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder()
                        .userInput("昨天和前天各门店净利润，不要用营业额或毛利替代")
                        .build(),
                7L,
                "admin");
        IntentExecuteResponse elasticity = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder().userInput("菜品价格弹性和95%置信区间").build(),
                7L,
                "admin");
        IntentExecuteResponse costMargin = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder().userInput("成本毛利先查哪几项？").build(),
                7L,
                "admin");
        IntentExecuteResponse prohibited = orchestrator.execute(
                "DEMO_REST",
                IntentExecuteRequest.builder().userInput("要提升毛利率，哪些事情今天先不要做？").build(),
                7L,
                "admin");

        assertThat(List.of(turnover, netProfit, elasticity, costMargin, prohibited))
                .allSatisfy(response -> assertThat(response.getIntentRecognized()).isTrue());
        assertThat(turnover.getMessage()).contains("翻台率").contains("翻台次数");
        assertThat(netProfit.getMessage()).contains("昨天").contains("前天");
        assertThat(elasticity.getMessage()).contains("95%置信区间").contains("缺少的数据");
        assertThat(costMargin.getMessage()).contains("我理解的目标").contains("检查顺序");
        assertThat(prohibited.getMessage()).contains("最小验证").doesNotContain("预计提升");
    }

    private void stubOwnerGateway(Map<String, Object> data) {
        when(toolExecutionGateway.execute(any(ToolExecutionCommand.class)))
                .thenAnswer(invocation -> {
                    ToolExecutionCommand command = invocation.getArgument(0);
                    return new ToolExecutionResult(
                            command.requestId(),
                            command.toolName(),
                            command.expectedDescriptorVersion(),
                            "audit-owner-action",
                            command.traceId(),
                            ToolExecutionStatus.SUCCEEDED,
                            objectMapper.valueToTree(Map.of("success", true, "data", data)),
                            "Tool execution succeeded",
                            false);
                });
    }

    private ExecutionPrincipal principal() {
        return new ExecutionPrincipal(
                "DEMO_REST",
                "RESTAURANT",
                "7",
                PrincipalType.USER,
                Set.of("restaurant_owner"),
                Set.of(),
                Set.of());
    }

    private ToolExecutionResult gatewayResult(
            ToolExecutionCommand command,
            ToolExecutionStatus status,
            com.fasterxml.jackson.databind.JsonNode payload) {
        return new ToolExecutionResult(
                command.requestId(),
                command.toolName(),
                command.expectedDescriptorVersion(),
                "audit-owner-action",
                command.traceId(),
                status,
                payload,
                "Gateway result",
                false);
    }
}
