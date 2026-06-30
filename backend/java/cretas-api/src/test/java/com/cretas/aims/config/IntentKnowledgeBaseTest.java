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

    // ========== Sprint 12 P0 (2026-05-23) — NL Routing Root-Cause Fix ==========
    //
    // Per AI 工厂 chat Goal v5 UI audit (docs/audits/sprint-11-ux-audit/), 12/12 UI cases
    // misrouted to DAILY_CUSTOMER_FOLLOWUP-style output. Root cause: orchestrator phrase
    // shortcut was UNREACHABLE for CONVERSATIONAL-classified inputs. Fix moves shortcut
    // to position #0.25 BEFORE handleEarlyQuestionTypeDetection. This test pins 12+ NL
    // phrase variations to RESTAURANT_ECONOMICS_ANALYSIS to prevent regression.

    @Test
    @DisplayName("Sprint 12 P0 — 12+ NL phrase variations route to RESTAURANT_ECONOMICS_ANALYSIS")
    void sprint12NlVariationsRoute() {
        String[] phrases = {
                // Period variants (none collide with existing mappings)
                "上月亏", "上月赚多少", "本月亏", "这个月亏",
                "本月赚多少", "上月赔多少",
                // 利润 / 损益 / 损溢 variants
                "利润分析", "利润情况", "损溢报告", "损溢情况",
                // 哪个/哪些菜 variants (NOT 哪些菜亏钱 — Round 6 reserves for dish_cost)
                "哪些菜赔钱", "哪个菜赔钱", "哪个菜在亏",
                // 经营 phrases
                "经营诊断", "经营分析", "经营情况怎么样",
                // 餐厅经营 phrases
                "餐厅经营", "门店经营", "今天生意怎么样", "店面经营",
                // 餐厅/门店成本 phrases (NOT generic "成本分析")
                "餐饮成本", "餐厅成本", "厨房成本",
        };
        for (String phrase : phrases) {
            Optional<String> result = kb.matchPhrase(phrase, "RESTAURANT");
            assertTrue(result.isPresent(),
                    "Sprint 12 phrase '" + phrase + "' must produce a match");
            assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", result.get(),
                    "Sprint 12 phrase '" + phrase
                            + "' must route to RESTAURANT_ECONOMICS_ANALYSIS, NOT misroute "
                            + "to DAILY_CUSTOMER_FOLLOWUP / RESTAURANT_DISH_COST_ANALYSIS / etc.");
        }
    }

    @Test
    @DisplayName("Sprint 12 P0 — Sprint 12 additions don't collide with Round 6 distinguished cases")
    void sprint12NoCollisionWithRound6Distinguished() {
        // CRITICAL regression test: Sprint 12 added many NL variations to RESTAURANT_ECONOMICS_ANALYSIS.
        // Must NOT pollute existing distinguished mappings:
        // - "哪些菜亏钱" remains RESTAURANT_DISH_COST_ANALYSIS (Round 6 dish_cost reserves)
        // - "成本分析" remains COST_TREND_ANALYSIS for FACTORY / RESTAURANT_DISH_COST_ANALYSIS for RESTAURANT

        Optional<String> dishCostPhrase = kb.matchPhrase("哪些菜亏钱", "RESTAURANT");
        assertEquals("RESTAURANT_DISH_COST_ANALYSIS", dishCostPhrase.orElse(""),
                "'哪些菜亏钱' must remain dish_cost (Sprint 12 must NOT collide)");

        Optional<String> factoryCost = kb.matchPhrase("成本分析", "FACTORY");
        assertEquals("COST_TREND_ANALYSIS", factoryCost.orElse(""),
                "'成本分析' FACTORY must remain COST_TREND_ANALYSIS (Sprint 12 must NOT collide)");

        Optional<String> restaurantCost = kb.matchPhrase("成本分析", "RESTAURANT");
        assertEquals("RESTAURANT_DISH_COST_ANALYSIS", restaurantCost.orElse(""),
                "'成本分析' RESTAURANT must remain RESTAURANT_DISH_COST_ANALYSIS (Sprint 12 must NOT collide)");
    }

    @Test
    @DisplayName("Sprint 12 P0 — Round 6 keywords still route (regression test)")
    void sprint12NoRegressionFromRound6() {
        // Make sure new additions don't break Round 6 entries
        Optional<String> r1 = kb.matchPhrase("帮我看上月损溢异常", "RESTAURANT");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", r1.orElse(""));

        Optional<String> r2 = kb.matchPhrase("损益分析", "RESTAURANT");
        assertEquals("RESTAURANT_ECONOMICS_ANALYSIS", r2.orElse(""));

        Optional<String> r3 = kb.matchPhrase("食材损耗", "RESTAURANT");
        assertEquals("RESTAURANT_WASTAGE_SUMMARY", r3.orElse(""),
                "Round 6 '食材损耗' must NOT regress to economics");
    }

    // =========================================================================
    // P1-A — leading time-prefix strip for restaurant gold time consistency
    // =========================================================================

    @Test
    @DisplayName("P1-A — time-prefixed query routes to the SAME intent as the base phrase")
    void timePrefixedQueryRoutesSameAsBasePhrase() {
        String expected = kb.matchPhrase("爆款", "RESTAURANT").orElse("BASE_NONE");
        assertEquals("RESTAURANT_BESTSELLER_QUERY", expected,
                "Baseline: '爆款' must map to RESTAURANT_BESTSELLER_QUERY");

        // These three FAIL direct phrase-match (the long absolute-date prefix pushes the input
        // past the short-input coverage threshold) — the leading-time-strip fallback rescues them.
        assertEquals(expected, kb.matchPhrase("2025年12月爆款", "RESTAURANT").orElse("NONE"),
                "'2025年12月爆款' must route to the same intent as '爆款' via time-strip");
        assertEquals(expected, kb.matchPhrase("最近30天爆款", "RESTAURANT").orElse("NONE"),
                "'最近30天爆款' must route to the same intent as '爆款' via time-strip");
        assertEquals(expected, kb.matchPhrase("2025年12月热门菜", "RESTAURANT").orElse("NONE"),
                "'2025年12月热门菜' must route to RESTAURANT_BESTSELLER_QUERY via time-strip");
    }

    @Test
    @DisplayName("P1-A — '本月哪个菜卖得好' routes to RESTAURANT_DISH_SALES_RANKING (same as base)")
    void monthPrefixedDishRankingRoutesSame() {
        String base = kb.matchPhrase("哪个菜卖得好", "RESTAURANT").orElse("NONE");
        assertEquals("RESTAURANT_DISH_SALES_RANKING", base);
        assertEquals(base, kb.matchPhrase("本月哪个菜卖得好", "RESTAURANT").orElse("NONE"));
        assertEquals(base, kb.matchPhrase("今年哪个菜卖得好", "RESTAURANT").orElse("NONE"));
        assertEquals(base, kb.matchPhrase("本季度哪个菜卖得好", "RESTAURANT").orElse("NONE"));
        assertEquals(base, kb.matchPhrase("2025年12月哪个菜卖得好", "RESTAURANT").orElse("NONE"));
    }

    @Test
    @DisplayName("P1-A — stripLeadingTimePhrase removes ONLY a leading time token, not mid/trailing")
    void stripLeadingTimePhraseRemovesOnlyLeading() {
        // Leading time tokens are removed (with optional trailing 的)
        assertEquals("哪个菜卖得好", kb.stripLeadingTimePhrase("本月哪个菜卖得好"));
        assertEquals("哪个菜卖得好", kb.stripLeadingTimePhrase("今年哪个菜卖得好"));
        assertEquals("哪个菜卖得好", kb.stripLeadingTimePhrase("本季度哪个菜卖得好"));
        assertEquals("哪个菜卖得好", kb.stripLeadingTimePhrase("今年的哪个菜卖得好"));
        assertEquals("爆款", kb.stripLeadingTimePhrase("2025年12月爆款"));
        assertEquals("爆款", kb.stripLeadingTimePhrase("最近30天爆款"));
        assertEquals("招牌菜", kb.stripLeadingTimePhrase("本年度的招牌菜"),
                "本年度 (longer) must be preferred over 本年 prefix");

        // Non-time phrases are untouched
        assertEquals("哪个菜卖得好", kb.stripLeadingTimePhrase("哪个菜卖得好"));
        assertEquals("销量最好的菜", kb.stripLeadingTimePhrase("销量最好的菜"));
        // A time-like word in the MIDDLE is NOT stripped (anchored at start only)
        assertEquals("统计本月营业额", kb.stripLeadingTimePhrase("统计本月营业额"));
    }

    @Test
    @DisplayName("P1-A — time-strip must NOT break a non-time phrase (no false strip)")
    void timeStripDoesNotBreakNonTimePhrase() {
        // '本周热销菜' is itself a phrase entry (contains 本周) — direct match must still win,
        // and the result must remain BESTSELLER, not be corrupted by the strip.
        assertEquals("RESTAURANT_BESTSELLER_QUERY",
                kb.matchPhrase("本周热销菜", "RESTAURANT").orElse("NONE"));
        // Plain dish-cost phrase unaffected by the new strip path.
        assertEquals("RESTAURANT_DISH_COST_ANALYSIS",
                kb.matchPhrase("哪些菜亏钱", "RESTAURANT").orElse("NONE"));
    }

    @Test
    @DisplayName("Phase 2a — bare avg-ticket phrase routes to store revenue rank")
    void bareAverageTicketRoutesToStoreRevenueRank() {
        assertEquals("RESTAURANT_STORE_REVENUE_RANK",
                kb.matchPhrase("客单价", "RESTAURANT").orElse("NONE"));
        assertEquals("RESTAURANT_STORE_REVENUE_RANK",
                kb.matchPhrase("青花椒大丸百货店的客单价呢", "RESTAURANT").orElse("NONE"));
        assertEquals("RESTAURANT_STORE_REVENUE_RANK",
                kb.matchPhrase("人均消费", "RESTAURANT").orElse("NONE"));
    }

    @Test
    @DisplayName("Restaurant review demo phrases route to review intents, not sales or factory analysis")
    void restaurantReviewDemoPhrasesRouteToReviewIntents() {
        assertEquals("RESTAURANT_REVIEW_SUMMARY",
                kb.matchPhrase("\u5927\u4f17\u70b9\u8bc4\u53e3\u7891\u600e\u4e48\u6837", "RESTAURANT").orElse("NONE"));
        assertEquals("RESTAURANT_REVIEW_GOOD_TAGS",
                kb.matchPhrase("\u54ea\u51e0\u4e2a\u83dc\u54c1\u53e3\u7891\u6700\u597d", "RESTAURANT").orElse("NONE"));
        assertEquals("RESTAURANT_REVIEW_COMPLAINT",
                kb.matchPhrase("\u4f4e\u661f\u8bc4\u4ef7\u5e94\u8be5\u600e\u4e48\u6539\u5584", "RESTAURANT").orElse("NONE"));
    }
}
