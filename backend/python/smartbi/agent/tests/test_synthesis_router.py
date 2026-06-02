"""Unit tests for the P2 comprehensive-synthesis router.

Boundary铁律 (spec §5.1 + §6 Task 2): the synthesis engine is an additive
path. It MUST match multi-dimension / holistic questions and MUST NOT steal
single-dimension queries that the existing accurate routes serve.
"""
from smartbi.agent.synthesis_router import match_comprehensive_synthesis


class TestMatchComprehensiveSynthesis:
    # --- MUST MATCH: explicit synthesis verb ---
    def test_explicit_synthesis_verb(self):
        assert match_comprehensive_synthesis("综合分析青花椒的评价和经营") is True

    def test_holistic_verb(self):
        assert match_comprehensive_synthesis("整体分析一下") is True

    def test_full_analysis_verb(self):
        assert match_comprehensive_synthesis("全面分析门店表现") is True

    def test_synthesis_diagnosis_verb(self):
        assert match_comprehensive_synthesis("综合诊断经营状况") is True

    def test_multi_dim_verb_split(self):
        # "综合" + "分析" group hit even with text between them.
        assert match_comprehensive_synthesis("帮我综合一下看分析") is True

    def test_multi_dimensional(self):
        assert match_comprehensive_synthesis("多维分析青花椒") is True

    # --- MUST MATCH: ≥2 distinct dimensions co-occur (no explicit verb) ---
    def test_review_plus_finance(self):
        assert match_comprehensive_synthesis("评价和经营的关系") is True

    def test_finance_plus_review(self):
        assert match_comprehensive_synthesis("经营和评价怎么样") is True

    def test_vip_plus_sales(self):
        assert match_comprehensive_synthesis("VIP和菜品的关系") is True

    def test_vip_plus_place(self):
        assert match_comprehensive_synthesis("VIP和门店的关系") is True

    def test_time_plus_finance(self):
        assert match_comprehensive_synthesis("各时段营收和评分关系") is True

    def test_platform_plus_review(self):
        assert match_comprehensive_synthesis("各平台评分对比和口碑") is True

    def test_place_plus_finance(self):
        assert match_comprehensive_synthesis("上海杭州两地营收对比") is True

    def test_customer_plus_sales(self):
        assert match_comprehensive_synthesis("会员喜欢哪些菜品") is True

    # --- MUST NOT MATCH: single-dimension queries (let existing routes serve) ---
    def test_review_only_not_match(self):
        assert match_comprehensive_synthesis("评价怎么样") is False

    def test_revenue_only_not_match(self):
        assert match_comprehensive_synthesis("总营收多少") is False

    def test_bestseller_only_not_match(self):
        assert match_comprehensive_synthesis("畅销菜品有哪些") is False

    def test_vip_only_not_match(self):
        # VIP alone (customer dim) — no second dimension, no synthesis verb.
        assert match_comprehensive_synthesis("VIP评价怎么样") is True  # review+customer = 2 dims

    def test_pure_vip_not_match(self):
        # "VIP多少人" — only customer dim, nothing else.
        assert match_comprehensive_synthesis("VIP有多少") is False

    def test_store_revenue_single_not_match(self):
        # "哪家店最赚钱" — place + finance? "赚钱" is in finance vocab? No: finance
        # vocab is 营收/营业额/经营/财务/客单价/收入/业绩/利润. "赚钱" not listed → place only.
        assert match_comprehensive_synthesis("哪家店最赚钱") is False

    def test_time_period_review_single_not_match(self):
        # "哪个时段评价好" — time + review = 2 dims → genuinely cross-dim, MATCH ok.
        assert match_comprehensive_synthesis("哪个时段评价好") is True

    def test_pure_time_not_match(self):
        # "哪个时段人多" — only time dim.
        assert match_comprehensive_synthesis("哪个时段人多") is False

    def test_dish_cost_single_not_match(self):
        assert match_comprehensive_synthesis("食材成本最高的菜") is False

    def test_empty_not_match(self):
        assert match_comprehensive_synthesis("") is False
        assert match_comprehensive_synthesis(None) is False  # type: ignore[arg-type]

    def test_greeting_not_match(self):
        assert match_comprehensive_synthesis("你好") is False
