package com.cretas.aims.service.intent.impl;

import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.IntentKnowledgeBase.ActionType;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the W0 margin-based ABSTAIN gate
 * ({@link IntentRecognitionPipelineServiceImpl#maybeAbstain}).
 *
 * <p>W0 Task 4 goal: when the recognition pipeline produces a <em>weak</em> top
 * intent (top1 &lt; 0.70) OR a <em>narrow</em> margin between the top two
 * candidates (top1 - top2 &lt; 0.15), do NOT silently commit to the best match.
 * Instead return an abstain result (bestMatch == null, matchMethod == NONE,
 * with a clarification question + top-2 candidates) so the frontend can ask the
 * user which intent they meant. NO calibration dependency.
 *
 * <p>W0 fast-follow scoping: abstain only fires when a write/destructive intent is
 * among the top-2 candidates. For pure read ambiguity the gate returns null so the
 * downstream RAG / LLM-reranking can auto-resolve without an answer-rate regression.
 *
 * <p>The ambiguous-query path ("xxx怎么样" → GENERAL_QUESTION) keeps precedence:
 * when {@code isAmbiguousQuery == true}, {@code maybeAbstain} must return null so
 * the existing ambiguous-reject branch owns that decision.
 *
 * @author Cretas Team
 * @since 2026-06-02
 */
@DisplayName("W0 margin-based ABSTAIN gate")
class IntentAbstainTest {

    private IntentRecognitionPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        // maybeAbstain reads writeGuardService; all 26 constructor deps can be null
        // (incl. the W1b negationTwinPolicy, unused by maybeAbstain).
        service = new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null);
        // WriteGuardService is stateless — instantiate the real one so hasWriteSuffix works.
        ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
    }

    private CandidateIntent candidate(String code, String name, double confidence) {
        return CandidateIntent.builder()
                .intentCode(code)
                .intentName(name)
                .confidence(confidence)
                .matchScore((int) (confidence * 100))
                .matchedKeywords(List.of(code.toLowerCase()))
                .matchMethod(MatchMethod.SEMANTIC)
                .build();
    }

    // ── cases where abstain MUST fire (write in play) ────────────────────────────

    @Test
    @DisplayName("narrow margin with write intent top-1 (SHIPMENT_CREATE@0.78 vs READ@0.70) -> abstain")
    void narrowMarginWriteTop1Abstains() {
        List<CandidateIntent> candidates = List.of(
                candidate("SHIPMENT_CREATE", "创建出货单", 0.78),
                candidate("REPORT_INVENTORY", "库存报表", 0.70));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "出货还是查库存", ActionType.CREATE, QuestionType.OPERATIONAL_COMMAND, "F001");

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch()).isNull();
        assertThat(result.getMatchMethod()).isEqualTo(MatchMethod.NONE);
        assertThat(result.getClarificationQuestion()).isNotNull();
        assertThat(result.getClarificationQuestion()).contains("创建出货单").contains("库存报表");
        assertThat(result.getTopCandidates()).hasSize(2);
        assertThat(result.getRequiresConfirmation()).isTrue();
    }

    @Test
    @DisplayName("narrow margin with write intent top-2 (READ@0.78 vs MATERIAL_BATCH_DELETE@0.70) -> abstain")
    void narrowMarginWriteTop2Abstains() {
        List<CandidateIntent> candidates = List.of(
                candidate("MATERIAL_BATCH_QUERY", "原料批次查询", 0.78),
                candidate("MATERIAL_BATCH_DELETE", "删除原料批次", 0.70));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "看批次还是删批次", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND, "F001");

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch()).isNull();
        assertThat(result.getMatchMethod()).isEqualTo(MatchMethod.NONE);
        assertThat(result.getClarificationQuestion()).isNotNull();
        assertThat(result.getClarificationQuestion()).contains("原料批次查询").contains("删除原料批次");
        assertThat(result.getTopCandidates()).hasSize(2);
        assertThat(result.getRequiresConfirmation()).isTrue();
    }

    @Test
    @DisplayName("low top1 single write candidate (SHIPMENT_CREATE@0.62) -> abstain")
    void lowTop1WriteSingleCandidateAbstains() {
        List<CandidateIntent> candidates = List.of(
                candidate("SHIPMENT_CREATE", "创建出货单", 0.62));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "帮我发个货", ActionType.CREATE, QuestionType.OPERATIONAL_COMMAND, "F001");

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch()).isNull();
        assertThat(result.getMatchMethod()).isEqualTo(MatchMethod.NONE);
        assertThat(result.getClarificationQuestion()).isNotNull();
        assertThat(result.getClarificationQuestion()).contains("创建出货单");
        // single candidate -> only one entry preserved
        assertThat(result.getTopCandidates()).hasSize(1);
    }

    // ── cases where abstain must NOT fire ────────────────────────────────────────

    @Test
    @DisplayName("narrow margin, pure READ-only pair (MATERIAL_BATCH_QUERY@0.78 vs REPORT_INVENTORY@0.70) -> null (downstream resolves)")
    void narrowMarginReadOnlyPairNoAbstain() {
        List<CandidateIntent> candidates = List.of(
                candidate("MATERIAL_BATCH_QUERY", "原料批次查询", 0.78),
                candidate("REPORT_INVENTORY", "库存报表", 0.70));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "批次还是库存", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND, "F001");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("low top1 single READ candidate (MATERIAL_BATCH_QUERY@0.62) -> null (downstream resolves)")
    void lowTop1ReadOnlySingleCandidateNoAbstain() {
        List<CandidateIntent> candidates = List.of(
                candidate("MATERIAL_BATCH_QUERY", "原料批次查询", 0.62));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "查批次", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND, "F001");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("wide margin (0.90 vs 0.40 = 0.50) and high top1 -> no abstain (confident, exits before write gate)")
    void confidentNoAbstain() {
        List<CandidateIntent> candidates = List.of(
                candidate("SHIPMENT_CREATE", "创建出货单", 0.90),
                candidate("REPORT_INVENTORY", "库存报表", 0.40));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "明确的查询", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND, "F001");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("ambiguous query (isAmbiguousQuery=true) with low top1 -> no abstain (ambiguous path owns it)")
    void ambiguousQueryDefersToOwnReject() {
        List<CandidateIntent> candidates = List.of(
                candidate("SHIPMENT_CREATE", "创建出货单", 0.62));

        IntentMatchResult result = service.maybeAbstain(
                candidates, true, "天气怎么样", ActionType.QUERY, QuestionType.GENERAL_QUESTION, "F001");

        assertThat(result).isNull();
    }
}
