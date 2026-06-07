package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C1 (restaurant-chat-qa) — business-type filtering of NEED_CLARIFICATION choices.
 *
 * <p>QA sweep on a RESTAURANT tenant (RES_3101_009, qhj_prod) found the disambiguation
 * "请选择更适合的能力" list leaking MANUFACTURING options (查询原料库存 / 查询生产批次) — noise
 * for a restaurant. The fix filters {@code buildCandidateActions} / {@code buildDefaultSuggestions}
 * / {@code ensureMinChoices} by {@code BusinessTypeScope.isCompatible(intentBusinessType, domain)}.
 *
 * <p>This test exercises the package-private builders with a mocked {@link IntentConfigManagementService}
 * (field-injected via reflection, mirroring the BusinessTypeGate dependency). It asserts:
 * <ul>
 *   <li>RESTAURANT factory → defaults contain restaurant intents, ZERO manufacturing intents.</li>
 *   <li>FACTORY factory → defaults unchanged (manufacturing intents preserved).</li>
 *   <li>{@code ensureMinChoices} pads a RESTAURANT clarification with restaurant (not manufacturing) intents.</li>
 *   <li>Fail-soft: when domain resolution throws, the FACTORY (original) defaults are used.</li>
 * </ul>
 */
@DisplayName("IntentExecutionOrchestrator — C1 business-type clarification filter")
class IntentClarificationBusinessTypeFilterTest {

    private IntentExecutionOrchestrator orchestrator;
    private IntentConfigManagementService configService;

    private static final String RESTAURANT_FACTORY = "RES_3101_009";
    private static final String MANUFACTURING_FACTORY = "F006";

    // Manufacturing intent action codes that must NEVER appear for a restaurant tenant.
    private static final List<String> MANUFACTURING_CODES =
            List.of("MATERIAL_BATCH_QUERY", "PROCESSING_BATCH_LIST", "DAILY_CUSTOMER_FOLLOWUP");

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
        configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
    }

    private AIIntentConfig intentWithBiz(String code, String businessType) {
        AIIntentConfig c = new AIIntentConfig();
        c.setIntentCode(code);
        c.setBusinessType(businessType);
        return c;
    }

    private List<String> actionCodes(List<IntentExecuteResponse.SuggestedAction> actions) {
        return actions.stream()
                .map(IntentExecuteResponse.SuggestedAction::getActionCode)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("RESTAURANT factory → defaults are restaurant intents, NO manufacturing intents")
    void restaurantDefaultsExcludeManufacturing() {
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");

        List<IntentExecuteResponse.SuggestedAction> defaults =
                orchestrator.buildDefaultSuggestions(RESTAURANT_FACTORY);
        List<String> codes = actionCodes(defaults);

        // No manufacturing leakage.
        assertThat(codes).doesNotContainAnyElementsOf(MANUFACTURING_CODES);
        // Restaurant defaults present.
        assertThat(codes).contains(
                "RESTAURANT_BESTSELLER_QUERY",
                "RESTAURANT_STORE_REVENUE_RANK",
                "RESTAURANT_ORDER_STATISTICS");
        // Sentinels still present.
        assertThat(codes).contains("REPHRASE", "SHOW_INTENTS");
    }

    @Test
    @DisplayName("FACTORY factory → defaults unchanged (manufacturing intents preserved)")
    void factoryDefaultsUnchanged() {
        when(configService.resolveBusinessDomain(MANUFACTURING_FACTORY)).thenReturn("FACTORY");

        List<IntentExecuteResponse.SuggestedAction> defaults =
                orchestrator.buildDefaultSuggestions(MANUFACTURING_FACTORY);
        List<String> codes = actionCodes(defaults);

        // Original manufacturing defaults preserved.
        assertThat(codes).contains("MATERIAL_BATCH_QUERY", "PROCESSING_BATCH_LIST");
        // No restaurant intents for a factory.
        assertThat(codes).doesNotContain("RESTAURANT_BESTSELLER_QUERY");
        assertThat(codes).contains("REPHRASE", "SHOW_INTENTS");
    }

    @Test
    @DisplayName("ensureMinChoices pads a RESTAURANT clarification with restaurant (not manufacturing) intents")
    void ensureMinChoicesRestaurantPadding() {
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");

        // Only sentinel actions present (0 data choices) → padding kicks in to reach minCount=2.
        List<IntentExecuteResponse.SuggestedAction> sentinelOnly = List.of(
                IntentExecuteResponse.SuggestedAction.builder()
                        .actionCode("REPHRASE").actionName("重新描述").build(),
                IntentExecuteResponse.SuggestedAction.builder()
                        .actionCode("SHOW_INTENTS").actionName("查看所有可用操作").build());

        List<IntentExecuteResponse.SuggestedAction> padded =
                orchestrator.ensureMinChoices(sentinelOnly, 2, RESTAURANT_FACTORY);
        List<String> codes = actionCodes(padded);

        assertThat(codes).doesNotContainAnyElementsOf(MANUFACTURING_CODES);
        assertThat(codes).contains("RESTAURANT_BESTSELLER_QUERY", "RESTAURANT_STORE_REVENUE_RANK");
    }

    @Test
    @DisplayName("isCandidateCompatible: FACTORY-tagged intents (including retagged MATERIAL_BATCH_QUERY/PROCESSING_BATCH_LIST) are filtered for restaurant")
    void isCandidateCompatibleFiltersByBusinessType() {
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");
        // SKU_GROSS_MARGIN was retagged COMMON→FACTORY in #485.
        when(configService.getIntentByCode(eq(RESTAURANT_FACTORY), eq("SKU_GROSS_MARGIN")))
                .thenReturn(Optional.of(intentWithBiz("SKU_GROSS_MARGIN", "FACTORY")));
        when(configService.getIntentByCode(eq(RESTAURANT_FACTORY), eq("RESTAURANT_BESTSELLER_QUERY")))
                .thenReturn(Optional.of(intentWithBiz("RESTAURANT_BESTSELLER_QUERY", "RESTAURANT")));
        // MATERIAL_BATCH_QUERY and PROCESSING_BATCH_LIST are retagged COMMON→FACTORY by
        // V20260928_03__retag_manufacturing_batch_intents.sql (COMMON-overload fix).
        // After the migration isCandidateCompatible must return false for a RESTAURANT tenant.
        when(configService.getIntentByCode(eq(RESTAURANT_FACTORY), eq("MATERIAL_BATCH_QUERY")))
                .thenReturn(Optional.of(intentWithBiz("MATERIAL_BATCH_QUERY", "FACTORY")));
        when(configService.getIntentByCode(eq(RESTAURANT_FACTORY), eq("PROCESSING_BATCH_LIST")))
                .thenReturn(Optional.of(intentWithBiz("PROCESSING_BATCH_LIST", "FACTORY")));

        String domain = orchestrator.resolveFactoryDomainSafe(RESTAURANT_FACTORY);
        assertThat(orchestrator.isCandidateCompatible(RESTAURANT_FACTORY, domain, "SKU_GROSS_MARGIN"))
                .as("FACTORY-tagged intent must be filtered out for a restaurant tenant").isFalse();
        assertThat(orchestrator.isCandidateCompatible(RESTAURANT_FACTORY, domain, "RESTAURANT_BESTSELLER_QUERY"))
                .as("restaurant intent must pass for a restaurant tenant").isTrue();
        // COMMON-overload fix: MATERIAL_BATCH_QUERY and PROCESSING_BATCH_LIST are now FACTORY-tagged
        // → filtered out for restaurant tenants via isCandidateCompatible.
        assertThat(orchestrator.isCandidateCompatible(RESTAURANT_FACTORY, domain, "MATERIAL_BATCH_QUERY"))
                .as("FACTORY-retagged MATERIAL_BATCH_QUERY must be filtered out for a restaurant tenant").isFalse();
        assertThat(orchestrator.isCandidateCompatible(RESTAURANT_FACTORY, domain, "PROCESSING_BATCH_LIST"))
                .as("FACTORY-retagged PROCESSING_BATCH_LIST must be filtered out for a restaurant tenant").isFalse();
    }

    @Test
    @DisplayName("FACTORY-retagged manufacturing intents still pass for a FACTORY tenant (zero regression)")
    void retaggedManufacturingIntentsPassForFactoryTenant() {
        when(configService.resolveBusinessDomain(MANUFACTURING_FACTORY)).thenReturn("FACTORY");
        when(configService.getIntentByCode(eq(MANUFACTURING_FACTORY), eq("MATERIAL_BATCH_QUERY")))
                .thenReturn(Optional.of(intentWithBiz("MATERIAL_BATCH_QUERY", "FACTORY")));
        when(configService.getIntentByCode(eq(MANUFACTURING_FACTORY), eq("PROCESSING_BATCH_LIST")))
                .thenReturn(Optional.of(intentWithBiz("PROCESSING_BATCH_LIST", "FACTORY")));

        String domain = orchestrator.resolveFactoryDomainSafe(MANUFACTURING_FACTORY);
        assertThat(orchestrator.isCandidateCompatible(MANUFACTURING_FACTORY, domain, "MATERIAL_BATCH_QUERY"))
                .as("FACTORY-tagged MATERIAL_BATCH_QUERY must pass for a factory tenant (no regression)").isTrue();
        assertThat(orchestrator.isCandidateCompatible(MANUFACTURING_FACTORY, domain, "PROCESSING_BATCH_LIST"))
                .as("FACTORY-tagged PROCESSING_BATCH_LIST must pass for a factory tenant (no regression)").isTrue();
    }

    @Test
    @DisplayName("Fail-soft: domain resolution throws → FACTORY (original) defaults used, no crash")
    void domainResolutionThrowsFailsSoftToFactoryDefaults() {
        when(configService.resolveBusinessDomain(anyString()))
                .thenThrow(new RuntimeException("config service hiccup"));

        List<IntentExecuteResponse.SuggestedAction> defaults =
                orchestrator.buildDefaultSuggestions(RESTAURANT_FACTORY);
        List<String> codes = actionCodes(defaults);

        // Fail-open to the original (manufacturing) defaults — never crash the clarification path.
        assertThat(codes).contains("MATERIAL_BATCH_QUERY", "PROCESSING_BATCH_LIST");
    }
}
