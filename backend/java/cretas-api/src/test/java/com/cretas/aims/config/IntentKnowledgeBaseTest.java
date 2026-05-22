package com.cretas.aims.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Round 6 — phrase mapping coverage test for RESTAURANT_ECONOMICS_ANALYSIS.
 *
 * <p>Background: Round 3 E2E discovered the spec'd customer happy-path keyword
 * "帮我看上月损溢异常" was misrouted to ALERT_ACTIVE by the LLM tier of the
 * recognition pipeline. Round 5 root-cause showed RESTAURANT_ECONOMICS_ANALYSIS
 * had ZERO phrase entries in {@link IntentKnowledgeBase}, so the phrase-match
 * layer never short-circuited the ambiguous LLM tier.
 *
 * <p>This test pins the mapping so future refactors don't silently re-introduce
 * the misroute. Per spec §2.11 Phase 4 DOD #1 the customer demo path requires
 * the literal keyword to route here.
 *
 * <p>POJO-style instantiation (no Spring context) — calls the public
 * {@code initDefaults()} explicitly. Mirrors the {@code MealPeriodNormalizerTest}
 * pattern used elsewhere for fast unit tests.
 */
class IntentKnowledgeBaseTest {

    private static IntentKnowledgeBase kb;

    @BeforeAll
    static void setUp() {
        kb = new IntentKnowledgeBase();
        kb.initDefaults();
    }

    @Test
    @DisplayName("Round 6 — literal spec keyword '帮我看上月损溢异常' routes to RESTAURANT_ECONOMICS_ANALYSIS (restaurant)")
    void specLiteralPhraseRoutesToEconomicsAnalysisForRestaurant() {
        Optional<String> result = kb.matchPhrase("帮我看上月损溢异常", "RESTAURANT");
        assertTrue(result.isPresent(), "Phrase '帮我看上月损溢异常' must produce a match");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get(),
                "Phrase '帮我看上月损溢异常' must route to RESTAURANT_ECONOMICS_ANALYSIS, "
                        + "not ALERT_ACTIVE (Round 3 misroute regression test)");
    }

    @Test
    @DisplayName("Round 6 — literal spec keyword also routes for FACTORY business domain")
    void specLiteralPhraseRoutesToEconomicsAnalysisForFactory() {
        Optional<String> result = kb.matchPhrase("帮我看上月损溢异常", "FACTORY");
        assertTrue(result.isPresent(), "Phrase '帮我看上月损溢异常' must produce a match for FACTORY too");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get(),
                "Phrase must route consistently regardless of business domain");
    }

    @Test
    @DisplayName("Round 6 — '损溢异常' variant routes to RESTAURANT_ECONOMICS_ANALYSIS")
    void sunyiYichangRoutes() {
        Optional<String> result = kb.matchPhrase("损溢异常", "RESTAURANT");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get());
    }

    @Test
    @DisplayName("Round 6 — '损益分析' variant routes to RESTAURANT_ECONOMICS_ANALYSIS")
    void sunyiFenxiRoutes() {
        Optional<String> result = kb.matchPhrase("损益分析", "RESTAURANT");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get());
    }

    @Test
    @DisplayName("Round 6 — '哪个菜亏钱' (singular) routes to RESTAURANT_ECONOMICS_ANALYSIS, "
            + "'哪些菜亏钱' (plural) preserved for RESTAURANT_DISH_COST_ANALYSIS")
    void nageCaiKuiQianRoutesDistinctly() {
        Optional<String> singular = kb.matchPhrase("哪个菜亏钱", "RESTAURANT");
        assertTrue(singular.isPresent());
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", singular.get(),
                "Singular '哪个菜亏钱' should route to economics-analysis (Round 6 addition)");

        Optional<String> plural = kb.matchPhrase("哪些菜亏钱", "RESTAURANT");
        assertTrue(plural.isPresent());
        assertEquals("RESTAURANT_DISH_COST_ANALYSIS", plural.get(),
                "Plural '哪些菜亏钱' must NOT regress from RESTAURANT_DISH_COST_ANALYSIS");
    }

    @Test
    @DisplayName("Round 6 — '食材损耗' kept routing to RESTAURANT_WASTAGE_SUMMARY (no regression)")
    void shicaiSunhaoNoRegression() {
        Optional<String> result = kb.matchPhrase("食材损耗", "RESTAURANT");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_WASTAGE_SUMMARY", result.get(),
                "'食材损耗' must NOT be reclassified to RESTAURANT_ECONOMICS_ANALYSIS");
    }

    @Test
    @DisplayName("Round 6 — '损耗异常' kept routing to RESTAURANT_WASTAGE_ANOMALY (no regression)")
    void sunhaoYichangNoRegression() {
        Optional<String> result = kb.matchPhrase("损耗异常", "RESTAURANT");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_WASTAGE_ANOMALY", result.get(),
                "'损耗异常' must NOT be reclassified to RESTAURANT_ECONOMICS_ANALYSIS");
    }

    @Test
    @DisplayName("Round 6 — '成本分析' kept routing to its existing mappings (no factory regression)")
    void chengbenFenxiNoFactoryRegression() {
        // Factory domain: existing mapping is COST_TREND_ANALYSIS (we did NOT override)
        Optional<String> factoryResult = kb.matchPhrase("成本分析", "FACTORY");
        assertTrue(factoryResult.isPresent());
        assertEquals("COST_TREND_ANALYSIS", factoryResult.get(),
                "Factory '成本分析' must remain COST_TREND_ANALYSIS (no Sprint 11 regression)");

        // Restaurant domain: existing mapping is RESTAURANT_DISH_COST_ANALYSIS
        Optional<String> restaurantResult = kb.matchPhrase("成本分析", "RESTAURANT");
        assertTrue(restaurantResult.isPresent());
        assertEquals("RESTAURANT_DISH_COST_ANALYSIS", restaurantResult.get(),
                "Restaurant '成本分析' must remain RESTAURANT_DISH_COST_ANALYSIS");
    }
}
