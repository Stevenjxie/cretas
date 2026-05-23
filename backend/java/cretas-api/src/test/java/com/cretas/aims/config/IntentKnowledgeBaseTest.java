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

    // ========== Sprint 11 Round 7 (2026-05-23) — prod-confirmed misroute fix ==========
    //
    // Background: Prod (jar built from main `1ad950937`) confirmed 3/4 of the spec'd
    // happy-path phrases STILL misroute via /execute endpoint, despite Round 6 phrase
    // additions. Round 7 expanded the phrase list AND added defense-in-depth at the
    // orchestrator layer. These tests pin the new Round 7 phrase additions.

    @Test
    @DisplayName("Round 7 — '上月成本' routes to RESTAURANT_ECONOMICS_ANALYSIS (prod-confirmed missing)")
    void shangyueChengbenRoutes() {
        Optional<String> result = kb.matchPhrase("上月成本", "RESTAURANT");
        assertTrue(result.isPresent(),
                "Phrase '上月成本' must produce a match (Round 7 addition)");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get(),
                "Phrase '上月成本' must route to RESTAURANT_ECONOMICS_ANALYSIS, "
                        + "not RESTAURANT_INGREDIENT_COST_TREND (prod 2026-05-23 misroute)");
    }

    @Test
    @DisplayName("Round 7 — '本月成本' routes to RESTAURANT_ECONOMICS_ANALYSIS (Round 7 addition)")
    void benyueChengbenRoutes() {
        Optional<String> result = kb.matchPhrase("本月成本", "RESTAURANT");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get(),
                "Phrase '本月成本' must route to RESTAURANT_ECONOMICS_ANALYSIS (Round 7 addition)");
    }

    @Test
    @DisplayName("Round 7 — '上月损益' / '上月损溢' both route to RESTAURANT_ECONOMICS_ANALYSIS")
    void shangyueSunyiRoutes() {
        Optional<String> sunyi = kb.matchPhrase("上月损益", "RESTAURANT");
        assertTrue(sunyi.isPresent(), "上月损益 must match");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", sunyi.get());

        Optional<String> sunyiAlt = kb.matchPhrase("上月损溢", "RESTAURANT");
        assertTrue(sunyiAlt.isPresent(), "上月损溢 must match");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", sunyiAlt.get());
    }

    @Test
    @DisplayName("Round 7 — '损益分析' (Round 6) still routes to RESTAURANT_ECONOMICS_ANALYSIS (regression)")
    void sunyiFenxiStillRoutes() {
        // Round 6 added this. Prod said /execute misrouted it to RESTAURANT_OPS_GROSS_MARGIN.
        // Phrase map test passes (proving Round 6 phrase data is correct) — orchestrator
        // shortcut in Round 7 ensures it actually fires at execute time.
        Optional<String> result = kb.matchPhrase("损益分析", "RESTAURANT");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get(),
                "Phrase '损益分析' must route to RESTAURANT_ECONOMICS_ANALYSIS, "
                        + "not RESTAURANT_OPS_GROSS_MARGIN (prod 2026-05-23 misroute)");
    }

    @Test
    @DisplayName("Round 7 — Factory '本月成本' / '上月成本' also route to RESTAURANT_ECONOMICS_ANALYSIS")
    void monthlyCostAlsoRoutesForFactory() {
        // Defensive: Round 7 added to BOTH maps; factory-mode workdesk queries also need it.
        Optional<String> result = kb.matchPhrase("上月成本", "FACTORY");
        assertTrue(result.isPresent());
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get());
    }
}
