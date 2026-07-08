"""Unit tests for ComprehensiveSynthesisEngine (spec §4.3 + §6 Tasks 4-6).

Mock P1 review_* + gold finance/products via monkeypatch; mock call_chain so no
LLM/DB needed. Asserts:
  - plan_dimensions gating.
  - _build_factbook gathers only plan dims; P1 absent → review None + next-action.
  - synthesize串联: grounded numbers from factbook; fact-check meta present;
    charts gated by plan; budget exhausted → degraded; cache hit → 0 token.
  - P0: RedactionScope is set during the LLM call AND sensitive store names are
    registered for egress (the captured payload would be redacted by the choke
    point — here we assert the scope + known_values are populated).
"""
import asyncio

import pytest

import smartbi.agent.synthesis_engine as se
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine, SynthesisResponse
from smartbi.agent.budget_tracker import BudgetCheckResult
from common.llm_redactor import current_redaction_scope


# --------------------------------------------------------------------------
# Fakes
# --------------------------------------------------------------------------
class FakeBudget:
    def __init__(self, blocked=False):
        self._blocked = blocked

    async def check_budget(self, factory_id, today=None):
        return BudgetCheckResult(blocked=self._blocked, tokens_used=10, tokens_cap=50000)

    async def consume(self, factory_id, tokens, today=None):
        return BudgetCheckResult(blocked=False, tokens_used=10 + tokens, tokens_cap=50000)


class FakeCache:
    def __init__(self, hit=None):
        self._hit = hit
        self.put_calls = []

    async def get(self, factory_id, q_hash):
        return self._hit

    async def put(self, factory_id, q_hash, answer, chart_config, tokens, ttl_hours=24):
        self.put_calls.append({"answer": answer, "chart_config": chart_config, "tokens": tokens})


def _engine(monkeypatch, *, budget=None, cache=None):
    eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=budget or FakeBudget(),
                                       cache=cache or FakeCache())
    return eng


# review_* / gold fakes the engine awaits (module-level functions imported into se).
def _install_data_fakes(monkeypatch, *, review_empty=False):
    async def fake_review_summary(pool, fid):
        if review_empty:
            return {"factory_id": fid, "total_reviews": 0, "dimension_scores": []}
        return {
            "total_reviews": 19845, "avg_star": 4.79, "avg_service": 4.80, "avg_env": 4.79,
            "avg_taste": 4.79, "low_star_count": 396, "high_star_count": 18139, "vip_count": 2485,
            "dimension_scores": [{"name": "星级", "value": 4.79}, {"name": "服务", "value": 4.80}],
        }

    async def fake_review_vip(pool, fid):
        return {"groups": [
            {"group": "VIP", "review_count": 2485, "avg_star": 4.50},
            {"group": "非VIP", "review_count": 17360, "avg_star": 4.83},
        ]}

    async def fake_review_platform(pool, fid):
        return {"platforms": [
            {"platform": "点评", "review_count": 19189, "avg_star": 4.80},
            {"platform": "美团", "review_count": 656, "avg_star": 4.57},
        ]}

    async def fake_review_time(pool, fid):
        return {"periods": [
            {"period": "午(11-14点)", "review_count": 5989, "avg_star": 4.85},
            {"period": "夜(22-4点)", "review_count": 479, "avg_star": 4.31},
        ], "null_period_count": 5420}

    async def fake_review_good(pool, fid):
        return {"high_star_count": 18139, "tags": [{"tag": "味道好", "count": 5998}]}

    async def fake_review_issues(pool, fid):
        return {"low_star_count": 396, "tags": [{"tag": "味道差", "count": 79}]}

    async def fake_review_worst(pool, fid, *, dim, order, top_n):
        return {"stores": [{"store": "鲜行者X顺德小馆", "low_star_count": 64, "review_count": 800}]}

    async def fake_finance(pool, fid, dr, *, top_n_stores=10):
        return {
            "start_date": dr[0].isoformat(), "end_date": dr[1].isoformat(),
            "total_revenue": 20640000.0, "bill_count": 141000, "avg_bill_value": 146.4,
            "store_count": 8, "day_count": 365,
            "top_stores": [{"store_id": 1, "store_name": "青花椒大融城店",
                            "revenue": 3500000.0, "bill_count": 24000}],
        }

    async def fake_top_products(pool, fid, dr, *, top_n=10, order="desc"):
        return {"top_products": [{"product_name": "藤椒鱼", "revenue": 1200000.0, "qty_sold": 30000}]}

    async def fake_channels(pool, fid, dr, *, top_n=10):
        return {"channels": [{"channel_name": "微信", "amount": 12000000.0, "share_pct": 58.1}]}

    async def fake_discounts(pool, fid, dr, *, top_n=10):
        return {"discounts": [{"discount_name": "满减券", "amount": 800000.0}]}

    async def fake_store_comparison(pool, fid, dr):
        return {"stores": [
            {"name": "甲店", "revenue": 1000000.0, "orderCount": 8000, "avgTicket": 125.0},
            {"name": "乙店", "revenue": 600000.0, "orderCount": 6000, "avgTicket": 100.0},
            {"name": "丙店", "revenue": 1400000.0, "orderCount": 7000, "avgTicket": 200.0},
        ], "medianRevenue": 1000000.0, "weakStores": ["乙店"]}

    monkeypatch.setattr(se, "review_summary", fake_review_summary)
    monkeypatch.setattr(se, "review_vip", fake_review_vip)
    monkeypatch.setattr(se, "review_platform", fake_review_platform)
    monkeypatch.setattr(se, "review_time_period", fake_review_time)
    monkeypatch.setattr(se, "review_good_tags", fake_review_good)
    monkeypatch.setattr(se, "review_dish_issues", fake_review_issues)
    monkeypatch.setattr(se, "review_store_ranking", fake_review_worst)
    monkeypatch.setattr(se, "finance_summary", fake_finance)
    monkeypatch.setattr(se, "store_comparison", fake_store_comparison)
    monkeypatch.setattr(se, "top_products", fake_top_products)
    monkeypatch.setattr(se, "channel_breakdown", fake_channels)
    monkeypatch.setattr(se, "discount_breakdown", fake_discounts)


# --------------------------------------------------------------------------
# plan_dimensions
# --------------------------------------------------------------------------
class TestPlanDimensions:
    def test_review_plus_finance(self):
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("综合分析评价和经营")
        assert plan["review"] and plan["finance"]

    def test_vip_cross(self):
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("VIP和菜品的关系")
        assert "vip_x_rating" in plan["cross"]
        assert plan["review"] and plan["sales"]

    def test_time_revenue_cross(self):
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("各时段营收关系")
        assert "time_x_review" in plan["cross"]

    def test_holistic_opens_all(self):
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("整体诊断")
        assert plan["review"] and plan["finance"] and plan["sales"]

    def test_attribution_detected_for_lag_question(self):
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("哪家店拖后腿，是客流还是客单价")
        assert plan["attribution"] is True

    def test_attribution_not_for_generic_finance(self):
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("总营收多少")
        assert plan["attribution"] is False

    def test_pure_attribution_does_not_trip_open_all(self):
        # "客流" is NOT a finance keyword → only attribution fires; the open-all
        # fallback must NOT then force review/finance/sales on.
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        plan = eng.plan_dimensions("哪家店客流拖后腿")
        assert plan["attribution"] is True
        assert not plan["review"] and not plan["finance"] and not plan["sales"]

    def test_attribution_detected_for_colloquial_phrasing(self):
        # F2 (2026-07-08 role-play): plain-speech attribution must trigger the
        # 客流×客单价 decomposition, not fall through to a review answer.
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        for q in ["十六家店里头哪家最不行，是没人来还是客人花的钱少",
                  "有的店生意就是做不起来",
                  "哪家店没人来"]:
            assert eng.plan_dimensions(q)["attribution"] is True, q

    def test_colloquial_neutral_store_query_not_attribution(self):
        # A neutral store ranking (positive superlative) must NOT trip attribution.
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        assert eng.plan_dimensions("哪家店订单最多")["attribution"] is False

    def test_shengyi_chabuduo_not_attribution(self):
        # 生意差 ⊂ 生意差不多 (neutral "roughly the same") — polarity inversion must
        # NOT trip the store decomposition (audit B#1).
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        for q in ["各店生意差不多", "哪家店生意差不多"]:
            assert eng.plan_dimensions(q)["attribution"] is False, q

    def test_bare_renshao_dropped_no_overreach(self):
        # bare 人少 dropped → a supplier/staffing question must not trip attribution
        # (audit B#2).
        eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=FakeBudget(), cache=FakeCache())
        assert eng.plan_dimensions("哪家供应商送货人少")["attribution"] is False


# --------------------------------------------------------------------------
# _build_factbook
# --------------------------------------------------------------------------
class TestBuildFactbook:
    def test_only_plan_dims_pulled(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        plan = {"review": True, "finance": True, "sales": False, "cross": ["vip_x_rating"]}
        fb = asyncio.run(eng._build_factbook("RES_3101_009", dr, plan, period="2025"))
        assert fb.review is not None
        assert fb.finance is not None
        assert fb.sales is None  # not in plan
        # cross hint built
        assert any("VIP" in h["description"] for h in fb.cross_hints)
        # honest notes
        assert se.NOTE_DISH_TAG_NOT_NAME in fb.notes
        assert se.NOTE_VIP_SIGNAL in fb.notes  # VIP 4.50 < 非VIP 4.83

    def test_attribution_dimension_populates_from_store_comparison(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        plan = {"review": False, "finance": False, "sales": False,
                "attribution": True, "cross": []}
        fb = asyncio.run(eng._build_factbook("F", dr, plan, period="2025"))
        assert fb.attribution is not None
        assert fb.attribution["laggard"]["store_name"] == "乙店"  # lowest ticket
        assert fb.attribution["primary_cause"] == "客单价"
        assert "门店拖后腿归因" in fb.to_prompt_text()
        assert "客流效应" in fb.to_facts_index()

    def test_review_empty_degrades_with_nextaction(self, monkeypatch):
        _install_data_fakes(monkeypatch, review_empty=True)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        plan = {"review": True, "finance": True, "sales": False, "cross": []}
        fb = asyncio.run(eng._build_factbook("RES_3101_009", dr, plan, period="2025"))
        assert fb.review is None
        assert plan["review"] is False  # plan flipped
        assert se.NOTE_REVIEW_ABSENT_NEXTACTION in fb.notes
        assert fb.finance is not None  # still has finance


# --------------------------------------------------------------------------
# collect_charts gating
# --------------------------------------------------------------------------
class TestCharts:
    def test_charts_gated_by_plan(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        plan = {"review": True, "finance": True, "sales": False, "cross": ["vip_x_rating", "time_x_review"]}
        fb = asyncio.run(eng._build_factbook("RES_3101_009", dr, plan, period="2025"))
        charts = eng.collect_charts(fb, plan)
        titles = [c["title"] for c in charts]
        # review + finance charts present, NO channel pie (sales not in plan)
        assert any("评价维度" in t for t in titles)
        assert any("VIP vs 非VIP" in t for t in titles)
        assert any("门店营收" in t for t in titles)
        assert not any("渠道" in t for t in titles)
        # all charts are valid ECharts-ish (type + series)
        for c in charts:
            assert "chartType" in c and "series" in c


# --------------------------------------------------------------------------
# synthesize end-to-end (mock call_chain)
# --------------------------------------------------------------------------
class TestSynthesize:
    def test_grounded_with_factcheck_and_redaction(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        scope_seen = {}

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            # P0 assertion: a RedactionScope must be active here, with the real
            # store name registered as a known value for egress.
            scope = current_redaction_scope()
            scope_seen["active"] = scope is not None
            scope_seen["known"] = dict(scope.known_values) if scope else {}
            # Return an answer that includes a WRONG number to exercise fact-check.
            return {
                "choices": [{"message": {"content": "平均星级 4.2，门店营收不错。"}}],
                "usage": {"total_tokens": 321},
            }

        monkeypatch.setattr(se, "call_chain", fake_call_chain)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析评价和经营", dr))
        assert isinstance(resp, SynthesisResponse)
        assert resp.source == "llm"
        assert resp.tokens == 321
        # P0: scope active + real store name registered for egress redaction
        assert scope_seen["active"] is True
        assert "青花椒大融城店" in scope_seen["known"]
        # grounding: wrong 4.2 backfilled with 实际 4.79
        assert "实际 4.79" in resp.answer
        assert resp.fact_check["reconciled"] is True
        # charts present
        assert resp.charts
        # cached
        assert eng._cache.put_calls

    def test_budget_exhausted_degraded(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        eng = _engine(monkeypatch, budget=FakeBudget(blocked=True))
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析", dr))
        assert resp.source == "degraded"
        assert resp.tokens == 0

    def test_cache_hit_zero_token(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        cache = FakeCache(hit={"answer": "缓存答案", "chart_config": {"charts": [{"chartType": "bar"}]}})
        eng = _engine(monkeypatch, cache=cache)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析", dr))
        assert resp.source == "cache"
        assert resp.tokens == 0
        assert resp.answer == "缓存答案"
        assert resp.charts  # restored from cache chart_config

    def test_llm_failure_degraded(self, monkeypatch):
        _install_data_fakes(monkeypatch)

        async def boom(slot, payload, chain=None, timeout=30.0):
            raise RuntimeError("all providers exhausted")

        monkeypatch.setattr(se, "call_chain", boom)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析评价和经营", dr))
        assert resp.source == "degraded"
        # factbook still attached for debugging
        assert resp.factbook_text


# --------------------------------------------------------------------------
# G1: distillation capture — synthesis path becomes observable/learnable
# --------------------------------------------------------------------------
class TestSynthesisCapture:
    @staticmethod
    def _fake_persist(sink):
        async def fake_persist(pool, source=None, task_type=None, input_text=None,
                               teacher_output=None, **kw):
            sink.append({
                "source": source, "task_type": task_type,
                "input_text": input_text, "teacher_output": teacher_output, **kw,
            })
        return fake_persist

    def test_llm_path_captured_to_corpus(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        captured = []

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            return {"choices": [{"message": {"content": "门店表现平稳。"}}],
                    "usage": {"total_tokens": 200}}

        monkeypatch.setattr(se, "call_chain", fake_call_chain)
        monkeypatch.setattr(se, "persist_distillation_sample", self._fake_persist(captured))
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        query = "overall diagnosis"
        resp = asyncio.run(eng.synthesize("RES_3101_009", query, dr))
        assert resp.source == "llm"
        assert len(captured) == 1
        c = captured[0]
        assert c["source"] == "synthesis"
        assert c["task_type"] == "synthesis"
        assert c["factory_id"] == "RES_3101_009"
        assert c["business_type"] == "restaurant"
        # answer captured verbatim (grounded output)
        assert c["teacher_output"] == resp.answer
        # input_text embeds the question + data context (teaches FROM data)
        assert query in c["input_text"]
        assert "数据上下文" in c["input_text"]
        # metadata carries the demand signal: raw query + family classification
        assert c["metadata"]["query"] == query
        assert c["metadata"]["question_family"] == se.classify_question_family(query)
        assert "grounding" in c["metadata"]

    def test_cache_hit_not_captured(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        captured = []
        cache = FakeCache(hit={"answer": "缓存答案", "chart_config": None})
        monkeypatch.setattr(se, "persist_distillation_sample", self._fake_persist(captured))
        eng = _engine(monkeypatch, cache=cache)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析", dr))
        assert resp.source == "cache"
        assert captured == []  # cache re-serve must not re-capture

    def test_degraded_not_captured(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        captured = []
        monkeypatch.setattr(se, "persist_distillation_sample", self._fake_persist(captured))
        eng = _engine(monkeypatch, budget=FakeBudget(blocked=True))
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析", dr))
        assert resp.source == "degraded"
        assert captured == []  # budget-exhausted degraded path must not capture

    def test_llm_failure_not_captured(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        captured = []

        async def boom(slot, payload, chain=None, timeout=30.0):
            raise RuntimeError("all providers exhausted")

        monkeypatch.setattr(se, "call_chain", boom)
        monkeypatch.setattr(se, "persist_distillation_sample", self._fake_persist(captured))
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析评价和经营", dr))
        assert resp.source == "degraded"
        assert captured == []  # LLM-failure degraded path must not capture (guards refactors)

    def test_factory_tenant_labeled_factory(self, monkeypatch):
        _install_data_fakes(monkeypatch)
        captured = []

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            return {"choices": [{"message": {"content": "门店表现平稳。"}}],
                    "usage": {"total_tokens": 200}}

        monkeypatch.setattr(se, "call_chain", fake_call_chain)
        monkeypatch.setattr(se, "persist_distillation_sample", self._fake_persist(captured))
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        # A Cretas factory tenant (F#####) reaching synthesis must be labeled
        # "factory", not the restaurant default — no corpus-bucket pollution.
        resp = asyncio.run(eng.synthesize("F001", "综合分析经营", dr))
        assert resp.source == "llm"
        assert captured[0]["business_type"] == "factory"

    def test_empty_factbook_low_quality_and_flagged(self, monkeypatch):
        captured = []

        async def empty(*a, **k):
            return None

        for name in ("review_summary", "review_vip", "review_platform",
                     "review_time_period", "review_good_tags", "review_dish_issues",
                     "review_store_ranking", "finance_summary", "store_comparison",
                     "top_products", "channel_breakdown", "discount_breakdown"):
            monkeypatch.setattr(se, name, empty)

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            return {"choices": [{"message": {"content": "暂无数据可分析。"}}],
                    "usage": {"total_tokens": 50}}

        monkeypatch.setattr(se, "call_chain", fake_call_chain)
        monkeypatch.setattr(se, "persist_distillation_sample", self._fake_persist(captured))
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析经营", dr))
        assert resp.source == "llm"
        # empty factbook → captured but quality=2 (excluded from training export)
        # + flagged, so the demand report still sees the question.
        assert captured[0]["quality"] == 2
        assert captured[0]["metadata"]["empty_factbook"] is True



# --------------------------------------------------------------------------
# P2 multi-turn memory (2026-07-09): bounded conversation-history window for
# resolving indirect follow-up references ("展开第三点"/"那家店呢"/"它呢").
# 🔒 GROUNDING: history must never be a number source — FactBook stays the
# sole source of every number, and FactReconciler still reconciles against the
# CURRENT FactBook only. See synthesis_engine.py module docstring + Rule.
# --------------------------------------------------------------------------
class TestConversationHistoryPromptInjection:
    def test_history_passed_prompt_contains_labeled_block(self, monkeypatch):
        # (a) history passed → prompt contains the labeled history block.
        _install_data_fakes(monkeypatch)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        fb = asyncio.run(eng._build_factbook(
            "RES_3101_009", dr,
            {"review": True, "finance": True, "sales": False, "cross": []},
            period="2025",
        ))
        history = [
            {"q": "上个月营收多少", "a_summary": "上月营收 200 万，环比增长 5%。"},
            {"q": "哪家店最差", "a_summary": "乙店客单价最低，是拖后腿的主因。"},
        ]
        prompt = eng._build_prompt("那家店呢", fb, "", conversation_history=history)
        assert se.HISTORY_BLOCK_HEADER in prompt
        assert "上个月营收多少" in prompt
        assert "乙店客单价最低" in prompt
        # history block must appear BEFORE the user question / FactBook so the
        # LLM resolves the referent first, then anchors numbers to FactBook.
        assert prompt.index(se.HISTORY_BLOCK_HEADER) < prompt.index("用户问：那家店呢")

    def test_no_history_prompt_byte_identical_backcompat(self, monkeypatch):
        # (b) no session/history → prompt unchanged (backcompat).
        _install_data_fakes(monkeypatch)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        fb = asyncio.run(eng._build_factbook(
            "RES_3101_009", dr,
            {"review": True, "finance": True, "sales": False, "cross": []},
            period="2025",
        ))
        prompt_default_arg = eng._build_prompt("综合分析评价和经营", fb, "")
        prompt_explicit_none = eng._build_prompt(
            "综合分析评价和经营", fb, "", conversation_history=None,
        )
        prompt_empty_list = eng._build_prompt(
            "综合分析评价和经营", fb, "", conversation_history=[],
        )
        assert prompt_default_arg == prompt_explicit_none == prompt_empty_list
        assert se.HISTORY_BLOCK_HEADER not in prompt_default_arg

    def test_history_block_carries_factbook_authority_label(self):
        # (d) history block carries the "数字以 FactBook 为准" label.
        history = [{"q": "上月营收", "a_summary": "200 万"}]
        block = se._build_history_block(history)
        assert se.HISTORY_BLOCK_HEADER in block
        assert "FactBook 为准" in block
        assert "绝不能" in block and "数字来源" in block

    def test_history_block_bounded_window_not_linear_growth(self):
        # Bounded window: only the last HISTORY_MAX_TURNS turns are rendered,
        # regardless of how many turns are passed in — this must NOT regress
        # into unbounded "linear-growth general chat" history.
        # Non-numeric single-letter markers (话题A..话题T) — numbers are now
        # redacted from history (audit P2 #1), so numeric ids like "问题16" would
        # all collapse to "问题[数值]"; letters survive and stay distinct.
        many_turns = [
            {"q": f"话题{chr(65 + i)}", "a_summary": f"答复{chr(65 + i)}"} for i in range(20)
        ]
        block = se._build_history_block(many_turns)
        rendered_count = sum(1 for i in range(20) if f"话题{chr(65 + i)}" in block)
        assert rendered_count == se.HISTORY_MAX_TURNS
        # the MOST RECENT turns are kept (not the oldest).
        assert "话题T" in block   # i=19
        assert "话题A" not in block  # i=0

    def test_history_block_handles_json_string_and_malformed_input(self):
        # Defense in depth: asyncpg may hand back turns_history as a raw JSON
        # string depending on codec registration (mirrors
        # build_context_block's own normalization).
        import json as _json
        history_list = [{"q": "问题A", "a_summary": "回答A"}]
        as_json_str = _json.dumps(history_list, ensure_ascii=False)
        assert "问题A" in se._build_history_block(as_json_str)
        assert se._build_history_block(None) == ""
        assert se._build_history_block([]) == ""
        assert se._build_history_block("not json{{{") == ""
        assert se._build_history_block([{"no_q_or_a": True}]) == ""

    def test_synthesize_with_history_still_reconciles_against_current_factbook(self, monkeypatch):
        # (c) FactReconciler still in the flow (unchanged) — even when
        # conversation_history is supplied, a wrong number in the LLM's answer
        # must still be backfilled from the CURRENT turn's FactBook, not from
        # anything in the history.
        _install_data_fakes(monkeypatch)

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            # sanity: the history block reached the LLM payload.
            user_msg = payload["messages"][-1]["content"]
            assert se.HISTORY_BLOCK_HEADER in user_msg
            return {
                "choices": [{"message": {"content": "平均星级 4.2，门店营收不错。"}}],
                "usage": {"total_tokens": 111},
            }

        monkeypatch.setattr(se, "call_chain", fake_call_chain)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        history = [{"q": "上月怎么样", "a_summary": "上月营收平稳，评分 4.79。"}]
        resp = asyncio.run(eng.synthesize(
            "RES_3101_009", "那这个月呢", dr, conversation_history=history,
        ))
        assert resp.source == "llm"
        # grounding unchanged: wrong 4.2 backfilled with the CURRENT FactBook's
        # actual 4.79 — history's own numbers ("上月营收平稳") never leak in.
        assert "实际 4.79" in resp.answer
        assert resp.fact_check["reconciled"] is True
        # history-bearing turns skip the narrative cache (read+write) — see
        # synthesize() docstring: cache key doesn't capture which parent turn
        # produced the answer, so caching/reusing it across sessions would be
        # an ungrounded reuse of a resolved reference.
        assert not eng._cache.put_calls

    def test_synthesize_without_history_cache_write_unaffected(self, monkeypatch):
        # Backward compat: conversation_history=None still writes to cache
        # exactly as before this change.
        _install_data_fakes(monkeypatch)

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            return {"choices": [{"message": {"content": "综合表现良好。"}}],
                    "usage": {"total_tokens": 90}}

        monkeypatch.setattr(se, "call_chain", fake_call_chain)
        eng = _engine(monkeypatch)
        import datetime
        dr = (datetime.date(2025, 1, 1), datetime.date(2025, 12, 31))
        resp = asyncio.run(eng.synthesize("RES_3101_009", "综合分析评价和经营", dr))
        assert resp.source == "llm"
        assert eng._cache.put_calls  # unchanged: no-history path still caches


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
