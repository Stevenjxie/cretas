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


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
