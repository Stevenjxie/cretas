package com.cretas.aims.service.intent.impl;

import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.conversation.ConversationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for X1 Part B — continuation-intent inheritance
 * ({@link IntentRecognitionPipelineServiceImpl#maybeAugmentContinuation}).
 *
 * <p>Scene (prod QA, RES_3101_009): a 2nd-turn follow-up that carries no domain word of its own
 * (pure time ellipsis "上个月呢" / pure pronoun "它" / pure particle "呢") lost the prior turn's
 * intent domain. The fix: when a session exists AND the prior turn resolved to a whitelisted
 * restaurant analytics intent AND the current query is a tight continuation marker, augment the
 * processed query with the prior intent's canonical phrase so the existing phrase pipeline routes
 * it back to the same domain.
 *
 * <p><b>Safety contract</b>: {@code maybeAugmentContinuation} is the ONLY new behavior, and the
 * production call site wraps it in {@code if (sessionId != null)}. These tests prove:
 * <ul>
 *   <li>continuation markers WITH a non-null whitelisted lastIntent ARE augmented;</li>
 *   <li>queries carrying their own domain noun (店/菜/营收…) are NOT augmented (fresh question);</li>
 *   <li>no session / no lastIntent / non-whitelisted lastIntent / long query → NOT augmented;</li>
 *   <li>a standalone "上个月呢" with NO context (mirrors a sessionless parity case) → NOT augmented.</li>
 * </ul>
 *
 * <p>The method is a pure function reading only its two parameters, so the heavy 26-dep service is
 * constructed with all-null collaborators (mirrors {@code IntentAbstainTest}).
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@DisplayName("X1 Part B — continuation-intent inheritance")
class IntentContinuationAugmentTest {

    @Test
    @DisplayName("bare time continuation preserves its augmented executable query")
    void bareTimeContinuationPreservesAugmentedExecutableQuery() {
        PreprocessedQuery result =
                IntentRecognitionPipelineServiceImpl.ensureContinuationFinalQuery(
                        "最近30天", "最近30天门店营收排行", null);

        assertThat(result.getOriginalInput()).isEqualTo("最近30天");
        assertThat(result.getNormalizedText()).isEqualTo("最近30天门店营收排行");
        assertThat(result.getFinalQuery()).isEqualTo("最近30天门店营收排行");
    }

    private IntentRecognitionPipelineServiceImpl newService() {
        // maybeAugmentContinuation touches no instance field — all 26 deps may be null.
        return new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null);
    }

    private ConversationContext contextWithLastIntent(String lastIntentCode) {
        return ConversationContext.builder()
                .sessionId("sess-1")
                .factoryId("RES_3101_009")
                .userId(9L)
                .lastIntentCode(lastIntentCode)
                .build();
    }

    // ── continuation markers that MUST be augmented ──────────────────────────────

    @Test
    @DisplayName("bare time '上个月呢' after RESTAURANT_REVENUE_TREND -> '上个月收入趋势' (revenue-trend phrase)")
    void bareTimeAfterRevenueTrendAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "上个月呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        // Augmented query must carry the canonical revenue-trend phrase so the existing
        // phrase pipeline (contains-match) routes it back to RESTAURANT_REVENUE_TREND.
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("上个月");
    }

    @Test
    @DisplayName("bare time without particle '本季度' inherits revenue-trend domain")
    void bareTimeNoParticleAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "本季度", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("本季度");
    }

    @Test
    @DisplayName("rolling window '近30天呢' inherits revenue-trend domain")
    void rollingWindowAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "近30天呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
    }

    @Test
    @DisplayName("day-granularity '今天呢' after RESTAURANT_REVENUE_TREND -> augments")
    void bareTimeTodayAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "今天呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("今天");
    }

    @Test
    @DisplayName("day-granularity '昨天' after RESTAURANT_REVENUE_TREND -> augments")
    void bareTimeYesterdayAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "昨天", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("昨天");
    }

    @Test
    @DisplayName("half-year '上半年呢' after RESTAURANT_REVENUE_TREND -> augments")
    void bareTimeHalfYearAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "上半年呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("上半年");
    }

    @Test
    @DisplayName("rolling window with 个 '近3个月' after RESTAURANT_REVENUE_TREND -> augments")
    void rollingWindowWithGeAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "近3个月", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("近3个月");
    }

    @Test
    @DisplayName("rolling window with 个 '最近3个月' (最近 branch) after RESTAURANT_REVENUE_TREND -> augments")
    void rollingWindowZuijinWithGeAugments() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "最近3个月", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNotNull();
        assertThat(result).contains("收入趋势");
        assertThat(result).startsWith("最近3个月");
    }

    @Test
    @DisplayName("pure particle '呢' after RESTAURANT_BESTSELLER_QUERY -> canonical bestseller phrase")
    void pureParticleInheritsPriorIntent() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "呢", contextWithLastIntent("RESTAURANT_BESTSELLER_QUERY"));
        assertThat(result).isEqualTo("畅销菜品");
    }

    @Test
    @DisplayName("pure pronoun '它' (coref unresolved) after bestseller -> inherit canonical phrase")
    void barePronounInheritsPriorIntent() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "它", contextWithLastIntent("RESTAURANT_BESTSELLER_QUERY"));
        assertThat(result).isEqualTo("畅销菜品");
    }

    // ── queries that MUST NOT be augmented (own domain noun = fresh question) ─────

    @Test
    @DisplayName("'那家店的客单价呢' carries domain nouns (店/客单价) -> NOT augmented (fresh)")
    void domainNounQueryNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        // 9 chars and carries 店 + 客单价 — a self-contained question, must route on its own.
        String result = service.maybeAugmentContinuation(
                "那家店的客单价呢", contextWithLastIntent("RESTAURANT_STORE_REVENUE_RANK"));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("'上个月营收趋势怎么样' carries domain noun (营收) -> NOT augmented")
    void fullQuestionWithDomainNounNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "上个月营收", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNull();
    }

    // ── guard conditions: NOT augmented ──────────────────────────────────────────

    @Test
    @DisplayName("null context (mirrors sessionless standalone query) -> NOT augmented")
    void nullContextNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        // The production call site never reaches here without a session, but prove the method
        // itself is null-safe: a standalone "上个月呢" with no context yields no augmentation.
        String result = service.maybeAugmentContinuation("上个月呢", null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("null lastIntentCode -> NOT augmented")
    void nullLastIntentNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "上个月呢", contextWithLastIntent(null));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("lastIntent not in whitelist (a write intent) -> NOT augmented")
    void nonWhitelistedLastIntentNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "上个月呢", contextWithLastIntent("RESTAURANT_DISH_DELETE"));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("query too long (> 8 chars, not a bare continuation) -> NOT augmented")
    void longQueryNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                "帮我看看上个月的情况呢怎么样啊", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("core has a verb/other content after stripping particle -> NOT augmented")
    void nonContinuationCoreNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        // "再查一下呢" — strips 呢 to "再查一下", which is neither bare time nor bare pronoun.
        String result = service.maybeAugmentContinuation(
                "再查一下呢", contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("null processedInput -> NOT augmented (null-safe)")
    void nullInputNotAugmented() {
        IntentRecognitionPipelineServiceImpl service = newService();
        String result = service.maybeAugmentContinuation(
                null, contextWithLastIntent("RESTAURANT_REVENUE_TREND"));
        assertThat(result).isNull();
    }
}
