package com.cretas.aims.service.intent.impl;

import com.cretas.aims.config.IntentKnowledgeBase.ActionType;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * <p>The ambiguous-query path ("xxx怎么样" → GENERAL_QUESTION) keeps precedence:
 * when {@code isAmbiguousQuery == true}, {@code maybeAbstain} must return null so
 * the existing ambiguous-reject branch owns that decision.
 *
 * <p>{@code maybeAbstain} is a pure, side-effect-free helper that reads only its
 * parameters, so the service is instantiated with null dependencies.
 *
 * @author Cretas Team
 * @since 2026-06-02
 */
@DisplayName("W0 margin-based ABSTAIN gate")
class IntentAbstainTest {

    private IntentRecognitionPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        // maybeAbstain uses no instance fields; all 25 constructor deps can be null.
        service = new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
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

    @Test
    @DisplayName("narrow margin (0.78 vs 0.70 = 0.08 < 0.15), not ambiguous -> abstain with top-2")
    void narrowMarginAbstains() {
        List<CandidateIntent> candidates = List.of(
                candidate("A", "意图甲", 0.78),
                candidate("B", "意图乙", 0.70));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "查一下A还是B", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND);

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch()).isNull();
        assertThat(result.getMatchMethod()).isEqualTo(MatchMethod.NONE);
        assertThat(result.getClarificationQuestion()).isNotNull();
        assertThat(result.getClarificationQuestion()).contains("意图甲").contains("意图乙");
        assertThat(result.getTopCandidates()).hasSize(2);
        assertThat(result.getRequiresConfirmation()).isTrue();
    }

    @Test
    @DisplayName("low top1 (0.62 < 0.70), single candidate, not ambiguous -> abstain with single-candidate message")
    void lowTop1Abstains() {
        List<CandidateIntent> candidates = List.of(
                candidate("A", "意图甲", 0.62));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "随便问问", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND);

        assertThat(result).isNotNull();
        assertThat(result.getBestMatch()).isNull();
        assertThat(result.getMatchMethod()).isEqualTo(MatchMethod.NONE);
        assertThat(result.getClarificationQuestion()).isNotNull();
        assertThat(result.getClarificationQuestion()).contains("意图甲");
        // single candidate -> only one entry preserved
        assertThat(result.getTopCandidates()).hasSize(1);
    }

    @Test
    @DisplayName("wide margin (0.90 vs 0.40 = 0.50) and high top1 -> no abstain (null)")
    void confidentNoAbstain() {
        List<CandidateIntent> candidates = List.of(
                candidate("A", "意图甲", 0.90),
                candidate("B", "意图乙", 0.40));

        IntentMatchResult result = service.maybeAbstain(
                candidates, false, "明确的查询", ActionType.QUERY, QuestionType.OPERATIONAL_COMMAND);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("ambiguous query (isAmbiguousQuery=true) with low top1 -> no abstain (ambiguous path owns it)")
    void ambiguousQueryDefersToOwnReject() {
        List<CandidateIntent> candidates = List.of(
                candidate("A", "意图甲", 0.62));

        IntentMatchResult result = service.maybeAbstain(
                candidates, true, "天气怎么样", ActionType.QUERY, QuestionType.GENERAL_QUESTION);

        assertThat(result).isNull();
    }
}
