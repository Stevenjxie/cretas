package com.cretas.aims.service.impl;

import com.cretas.aims.dto.intent.RouteDecision;
import com.cretas.aims.dto.intent.RouteDecision.CandidateMatch;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W0 Task 3: twin-pair margin guard in SemanticRouterServiceImpl.
 *
 * Tests the package-private {@code decideFromScored()} helper directly,
 * using hand-built {@code ScoredIntent} lists.  No embedding service or
 * Spring context required.
 *
 * Cases:
 * 1. twin + narrow margin (0.05 < 0.08) → NEED_RERANKING
 * 2. twin + wide  margin (0.20 >= 0.08) → DIRECT_EXECUTE
 * 3. non-twin + narrow margin (0.03)    → DIRECT_EXECUTE  (margin guard only fires for twins)
 * 4. single candidate list             → DIRECT_EXECUTE  (no top-2 to compare)
 * 5. isTwinPair symmetric: (READ, WRITE) also matches → NEED_RERANKING
 */
@ExtendWith(MockitoExtension.class)
class SemanticRouterTwinMarginTest {

    @Mock
    private IntentEmbeddingCacheService embeddingCacheService;
    @Mock
    private AIIntentConfigRepository intentConfigRepository;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private RequestScopedEmbeddingCache requestScopedCache;

    private SemanticRouterServiceImpl router;

    @BeforeEach
    void setUp() {
        router = new SemanticRouterServiceImpl(
                embeddingCacheService, intentConfigRepository, embeddingClient, requestScopedCache);
        // Set thresholds matching application defaults so DIRECT_EXECUTE branch is reachable
        ReflectionTestUtils.setField(router, "directExecuteThreshold", 0.88);
        ReflectionTestUtils.setField(router, "rerankingThreshold", 0.70);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Build a minimal AIIntentConfig with only intentCode set. */
    private static AIIntentConfig intent(String code) {
        return AIIntentConfig.builder().intentCode(code).intentName(code).build();
    }

    /**
     * Build a ScoredIntent via the package-private constructor.
     * ScoredIntent fields: intentCode (String), intent (AIIntentConfig), score (double).
     */
    private static SemanticRouterServiceImpl.ScoredIntent si(String code, double score) {
        return new SemanticRouterServiceImpl.ScoredIntent(code, intent(code), score);
    }

    private static List<CandidateMatch> emptyCandidates() {
        return Collections.emptyList();
    }

    // ── test cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case 1: twin pair + narrow margin (0.05) → NEED_RERANKING")
    void twinNarrowMargin_shouldDowngradeToReranking() {
        // SHIPMENT_CREATE vs SHIPMENT_QUERY: canonical twin pair
        // margin = 0.81 - 0.76 = 0.05 < 0.08 threshold
        List<SemanticRouterServiceImpl.ScoredIntent> scored = List.of(
                si("SHIPMENT_CREATE", 0.81),
                si("SHIPMENT_QUERY", 0.76)
        );

        RouteDecision decision = router.decideFromScored(scored, emptyCandidates(), "创建出货", 10L);

        assertEquals(RouteDecision.RouteType.NEED_RERANKING, decision.getRouteType(),
                "Narrow-margin twin pair must downgrade to NEED_RERANKING");
    }

    @Test
    @DisplayName("Case 2: twin pair + wide margin (0.20) → DIRECT_EXECUTE")
    void twinWideMargin_shouldDirectExecute() {
        // margin = 0.90 - 0.70 = 0.20 >= 0.08 threshold — high confidence, no downgrade
        // SHIPMENT_CREATE is in SEMANTIC_GUARD_INTENTS so we use SHIPMENT_UPDATE which is NOT in the guard set
        List<SemanticRouterServiceImpl.ScoredIntent> scored = List.of(
                si("SHIPMENT_UPDATE", 0.90),
                si("SHIPMENT_QUERY", 0.70)
        );

        RouteDecision decision = router.decideFromScored(scored, emptyCandidates(), "更新出货", 10L);

        assertEquals(RouteDecision.RouteType.DIRECT_EXECUTE, decision.getRouteType(),
                "Wide-margin twin pair must proceed to DIRECT_EXECUTE");
    }

    @Test
    @DisplayName("Case 3: non-twin pair + narrow margin (0.03) → DIRECT_EXECUTE")
    void nonTwinNarrowMargin_shouldDirectExecute() {
        // REPORT_INVENTORY and ORDER_TODAY are NOT a twin pair
        // margin = 0.80 - 0.77 = 0.03 < 0.08, but guard should NOT fire
        // NOTE: ORDER_TODAY is in SEMANTIC_GUARD_INTENTS, so use it as second candidate
        //       and use REPORT_INVENTORY (not in guard set) as top-1
        //       → only the twin check is relevant here; top-1 is not guarded
        // Use two plain read-only intents that are not in SEMANTIC_GUARD_INTENTS or twin pairs
        List<SemanticRouterServiceImpl.ScoredIntent> scored = List.of(
                si("SUPPLIER_QUERY", 0.93),
                si("CUSTOMER_STATS", 0.90)
        );
        // CUSTOMER_STATS IS in SEMANTIC_GUARD_INTENTS (as second candidate, irrelevant)
        // SUPPLIER_QUERY is NOT in SEMANTIC_GUARD_INTENTS and NOT a twin pair with CUSTOMER_STATS
        // margin = 0.03 < 0.08 but no twin match → DIRECT_EXECUTE

        RouteDecision decision = router.decideFromScored(scored, emptyCandidates(), "查询供应商", 10L);

        assertEquals(RouteDecision.RouteType.DIRECT_EXECUTE, decision.getRouteType(),
                "Non-twin narrow margin must NOT downgrade — proceeds to DIRECT_EXECUTE");
    }

    @Test
    @DisplayName("Case 4: single candidate list → DIRECT_EXECUTE (no top-2, guard skipped)")
    void singleCandidate_shouldDirectExecute() {
        List<SemanticRouterServiceImpl.ScoredIntent> scored = List.of(
                si("SHIPMENT_UPDATE", 0.95)
        );

        RouteDecision decision = router.decideFromScored(scored, emptyCandidates(), "单候选", 5L);

        assertEquals(RouteDecision.RouteType.DIRECT_EXECUTE, decision.getRouteType(),
                "With only one candidate there is no margin to compare — must DIRECT_EXECUTE");
    }

    @Test
    @DisplayName("Case 5: isTwinPair is symmetric — (READ, WRITE) order also fires downgrade")
    void twinSymmetric_readWriteOrder_shouldDowngrade() {
        // Pair stored as "SHIPMENT_CREATE|SHIPMENT_QUERY" (WRITE|READ)
        // Reverse order: top-1 = SHIPMENT_QUERY (read), top-2 = SHIPMENT_CREATE (write)
        // margin = 0.82 - 0.78 = 0.04 < 0.08
        List<SemanticRouterServiceImpl.ScoredIntent> scored = List.of(
                si("SHIPMENT_QUERY", 0.82),
                si("SHIPMENT_CREATE", 0.78)
        );

        RouteDecision decision = router.decideFromScored(scored, emptyCandidates(), "查询出货情况", 10L);

        assertEquals(RouteDecision.RouteType.NEED_RERANKING, decision.getRouteType(),
                "isTwinPair must match in both directions — symmetric check must fire");
    }
}
