package com.cretas.aims.service.intent.impl;

import com.cretas.aims.ai.tool.NegationTwinPolicy;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.intent.MultiIntentResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.QueryPreprocessorService.NegationKind;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W1b T4: multi-intent SingleIntentMatch ↔ CandidateIntent mapping in
 * {@link IntentRecognitionPipelineServiceImpl#applyMultiIntentNegationPolicy}.
 *
 * <p>Drives the package-private method directly with a real {@link NegationTwinPolicy}
 * and a mocked {@link QueryPreprocessorService} so each {@link NegationKind} can be
 * forced. No Spring context (T5 owns the full-pipeline E2E).</p>
 *
 * Key invariants under test:
 *  - VETO_READ → suppressed to a single-intent clarification (isMultiIntent=false, empty intents).
 *  - VETO_WRITE → the vetoed write (with a read twin) becomes the read twin; writes without a
 *    twin are dropped; a non-write intent in the same query survives.
 *  - NONE (true compound query, all reads) → NOTHING is pruned; all intents survive.
 */
class IntentPipelineMultiIntentNegationTest {

    private QueryPreprocessorService queryPreprocessorService;
    private IntentKnowledgeBase knowledgeBase;
    private IntentConfigManagementService configService;
    private IntentRecognitionPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        queryPreprocessorService = mock(QueryPreprocessorService.class);
        knowledgeBase = mock(IntentKnowledgeBase.class);
        configService = mock(IntentConfigManagementService.class);
        NegationTwinPolicy policy = new NegationTwinPolicy(new WriteGuardService());

        // applyMultiIntentNegationPolicy only touches: queryPreprocessorService(15), knowledgeBase(22),
        // configService(25), negationTwinPolicy(26). The remaining 22 deps are unused here → null.
        service = new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,            // 1-5
                null, null, null, null, null,            // 6-10
                null, null, null, null,                  // 11-14
                queryPreprocessorService,                // 15
                null, null, null, null, null, null,      // 16-21
                knowledgeBase,                           // 22
                null, null,                              // 23-24
                configService,                           // 25
                policy);                                 // 26

        // configResolver returns a config whose sensitivity is null → isWriteIntent falls back to
        // name-suffix (so INVENTORY_CLEAR/_CREATE etc. are still recognized as writes via suffix).
        when(configService.getIntentConfigByCode(anyString(), anyString()))
                .thenAnswer(inv -> {
                    AIIntentConfig c = new AIIntentConfig();
                    c.setIntentCode(inv.getArgument(1));
                    return c;
                });
        // default detectActionType → QUERY (read-phrased) unless a test overrides.
        when(knowledgeBase.detectActionType(anyString())).thenReturn(IntentKnowledgeBase.ActionType.QUERY);
    }

    private MultiIntentResult.SingleIntentMatch m(String code, double conf, int order) {
        return MultiIntentResult.SingleIntentMatch.builder()
                .intentCode(code).intentName(code).confidence(conf).executionOrder(order).build();
    }

    private MultiIntentResult multi(List<MultiIntentResult.SingleIntentMatch> intents) {
        return MultiIntentResult.builder()
                .isMultiIntent(intents.size() > 1)
                .intents(intents)
                .executionStrategy(MultiIntentResult.ExecutionStrategy.PARALLEL)
                .overallConfidence(0.75)
                .reasoning("classifier")
                .build();
    }

    private void forceKind(NegationKind kind) {
        when(queryPreprocessorService.detectNegationVeto(anyString(), any())).thenReturn(kind);
    }

    @Test
    @DisplayName("VETO_READ → suppressed to single-intent clarification, no write survives")
    void vetoRead_suppressesToClarification() {
        forceKind(NegationKind.VETO_READ);
        // "不用查库存了" classifier might emit INVENTORY_CLEAR (write) + a read.
        MultiIntentResult in = multi(List.of(
                m("INVENTORY_CLEAR", 0.75, 1),
                m("MATERIAL_BATCH_QUERY", 0.72, 2)));

        MultiIntentResult out = service.applyMultiIntentNegationPolicy(in, "不用查库存了", "F001");

        assertThat(out.isMultiIntent()).isFalse();
        assertThat(out.getIntents()).isEmpty();
        assertThat(out.getReasoning()).contains("取消");
    }

    @Test
    @DisplayName("VETO_WRITE → write with read twin becomes the twin; co-occurring read survives")
    void vetoWrite_convertsWriteToTwin_keepsRead() {
        forceKind(NegationKind.VETO_WRITE);
        // "别开始生产，顺便看看物料" → PROCESSING_BATCH_START (write, twin=PROCESSING_BATCH_LIST) + MATERIAL_BATCH_QUERY (read)
        MultiIntentResult in = multi(List.of(
                m("PROCESSING_BATCH_START", 0.85, 1),
                m("MATERIAL_BATCH_QUERY", 0.70, 2)));

        MultiIntentResult out = service.applyMultiIntentNegationPolicy(in, "别开始生产顺便看物料", "F001");

        List<String> codes = out.getIntents().stream()
                .map(MultiIntentResult.SingleIntentMatch::getIntentCode).toList();
        assertThat(codes).containsExactly("PROCESSING_BATCH_LIST", "MATERIAL_BATCH_QUERY");
        // safety invariant: no write code present
        assertThat(codes).doesNotContain("PROCESSING_BATCH_START");
    }

    @Test
    @DisplayName("VETO_WRITE → write WITHOUT a read twin is dropped (safety), read kept")
    void vetoWrite_dropsWriteWithoutTwin() {
        forceKind(NegationKind.VETO_WRITE);
        // ORDER_CREATE has no twin entry → dropped; ORDER_LIST (read) survives.
        MultiIntentResult in = multi(List.of(
                m("ORDER_CREATE", 0.80, 1),
                m("ORDER_LIST", 0.70, 2)));

        MultiIntentResult out = service.applyMultiIntentNegationPolicy(in, "别下单只看订单", "F001");

        List<String> codes = out.getIntents().stream()
                .map(MultiIntentResult.SingleIntentMatch::getIntentCode).toList();
        assertThat(codes).containsExactly("ORDER_LIST");
        assertThat(codes).doesNotContain("ORDER_CREATE");
        // list collapsed to a single surviving read → no longer a multi-intent.
        assertThat(out.isMultiIntent()).isFalse();
    }

    @Test
    @DisplayName("NONE compound query (all reads) → nothing pruned, all intents survive")
    void none_trueCompoundQuery_notPruned() {
        forceKind(NegationKind.NONE);
        // "查库存和今天的销售" → two distinct reads, no write, no veto.
        MultiIntentResult in = multi(List.of(
                m("INVENTORY_QUERY", 0.82, 1),
                m("RESTAURANT_DAILY_REVENUE", 0.79, 2)));

        MultiIntentResult out = service.applyMultiIntentNegationPolicy(in, "查库存和今天的销售", "F001");

        assertThat(out.isMultiIntent()).isTrue();
        List<String> codes = out.getIntents().stream()
                .map(MultiIntentResult.SingleIntentMatch::getIntentCode).toList();
        assertThat(codes).containsExactlyInAnyOrder("INVENTORY_QUERY", "RESTAURANT_DAILY_REVENUE");
    }

    @Test
    @DisplayName("null multi → returned as-is (fail-safe)")
    void nullMulti_returnedAsIs() {
        assertThat(service.applyMultiIntentNegationPolicy(null, "anything", "F001")).isNull();
    }
}
