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
from smartbi.agent.factbook import FactBook
from smartbi.agent.budget_tracker import BudgetCheckResult
from smartbi.agent.dimension_catalog import missing_status
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
    def __init__(self, hit=None, semantic_hit=None):
        self._hit = hit
        self._semantic_hit = semantic_hit
        self.put_calls = []
        self.get_semantic_calls = []

    async def get(self, factory_id, q_hash):
        return self._hit

    async def put(self, factory_id, q_hash, answer, chart_config, tokens, ttl_hours=24,
                   *, question_embedding=None, window_key=None, plan_key=None):
        # 2026-07-10 semantic cache fallback: put() gained 3 optional kwargs
        # (question_embedding/window_key/plan_key) — accepted here (and
        # recorded) so pre-existing tests asserting on answer/chart_config/
        # tokens are unaffected; new semantic-path tests can assert on these.
        self.put_calls.append({
            "answer": answer, "chart_config": chart_config, "tokens": tokens,
            "question_embedding": question_embedding, "window_key": window_key,
            "plan_key": plan_key,
        })

    async def get_semantic(self, factory_id, question_embedding, window_key, plan_key,
                            *, min_similarity=0.90):
        self.get_semantic_calls.append({
            "factory_id": factory_id, "window_key": window_key, "plan_key": plan_key,
        })
        return self._semantic_hit


def _engine(monkeypatch, *, budget=None, cache=None):
    # 2026-07-10 semantic cache fallback: synthesize() now calls module-level
    # _get_embedding(question) on every non-history call. Default it to a
    # no-op (returns None, like an embedding-service outage) so pre-existing
    # tests keep their old behavior (no real gRPC dial, no semantic lookup)
    # unless a test explicitly monkeypatches se._get_embedding itself.
    async def _no_embedding(text):
        return None
    monkeypatch.setattr(se, "_get_embedding", _no_embedding)

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

    async def fake_finance(
        pool,
        fid,
        dr,
        *,
        top_n_stores=10,
        store_names=None,
    ):
        return {
            "start_date": dr[0].isoformat(), "end_date": dr[1].isoformat(),
            "total_revenue": 20640000.0, "bill_count": 141000, "avg_bill_value": 146.4,
            "store_count": 8, "day_count": 365,
            "store_names": list(store_names) if store_names is not None else None,
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


class TestExplicitRestaurantStoreScope:
    def test_resolves_only_unambiguous_canonical_store_names(self, monkeypatch):
        monkeypatch.setattr(
            se,
            "extract_store_mentions",
            lambda _q: ["最近30天青花椒南方百联店"],
        )

        async def canonicalize(_pool, _factory_id, mention):
            assert mention == "最近30天青花椒南方百联店"
            return ["青花椒南方百联店"]

        monkeypatch.setattr(se, "_canonicalize_store_mention", canonicalize)
        resolved = asyncio.run(se._resolve_synthesis_store_scope(
            object(),
            "DEMO_REST",
            "最近30天青花椒南方百联店经营情况",
        ))
        assert resolved == ("青花椒南方百联店",)

    def test_ambiguous_store_scope_fails_closed(self, monkeypatch):
        monkeypatch.setattr(
            se,
            "extract_store_mentions",
            lambda _q: ["南方店"],
        )

        async def canonicalize(_pool, _factory_id, _mention):
            return ["青花椒南方百联店", "青花椒南方商城店"]

        monkeypatch.setattr(se, "_canonicalize_store_mention", canonicalize)
        resolved = asyncio.run(se._resolve_synthesis_store_scope(
            object(),
            "DEMO_REST",
            "南方店经营情况",
        ))
        assert resolved == ()

    def test_scoped_synthesis_skips_shared_semantic_cache(
        self, monkeypatch,
    ):
        cache = FakeCache(semantic_hit={
            "answer": "错误的全部门店缓存",
            "chart_config": {},
        })
        eng = _engine(
            monkeypatch,
            budget=FakeBudget(blocked=True),
            cache=cache,
        )
        captured = {}

        async def resolve_scope(_pool, _factory_id, _question):
            return ("青花椒南方百联店",)

        async def no_embedding(_text):
            raise AssertionError("scoped synthesis must not use semantic cache")

        async def build_factbook(
            _factory_id,
            _date_range,
            _plan,
            *,
            period,
            store_names=None,
        ):
            captured["period"] = period
            captured["store_names"] = store_names
            return FactBook(
                period=period,
                finance={
                    "total_revenue": 1000.0,
                    "bill_count": 10,
                    "avg_bill_value": 100.0,
                    "store_count": 1,
                    "top_stores": [{
                        "store_name": "青花椒南方百联店",
                        "revenue": 1000.0,
                        "bill_count": 10,
                    }],
                },
            )

        monkeypatch.setattr(se, "_resolve_synthesis_store_scope", resolve_scope)
        monkeypatch.setattr(se, "_get_embedding", no_embedding)
        monkeypatch.setattr(eng, "_build_factbook", build_factbook)

        import datetime
        response = asyncio.run(eng.synthesize(
            "DEMO_REST",
            "最近30天青花椒南方百联店经营情况",
            (datetime.date(2026, 6, 26), datetime.date(2026, 7, 25)),
        ))

        assert response.source == se.RESULT_SOURCE_DETERMINISTIC
        assert cache.get_semantic_calls == []
        assert captured["store_names"] == ("青花椒南方百联店",)
        assert "门店范围：青花椒南方百联店" in captured["period"]


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

    def test_store_scope_filters_internal_facts_and_labels_external_context(
        self, monkeypatch,
    ):
        captured = {
            "finance_store_names": None,
            "trend_store_names": None,
            "resolver_queries": [],
        }

        async def finance(
            _pool,
            _factory_id,
            _date_range,
            *,
            top_n_stores,
            store_names,
        ):
            captured["finance_store_names"] = store_names
            return {
                "total_revenue": 1000.0,
                "bill_count": 10,
                "avg_bill_value": 100.0,
                "store_count": 1,
                "day_count": 30,
                "store_names": list(store_names),
                "top_stores": [{
                    "store_name": store_names[0],
                    "revenue": 1000.0,
                    "bill_count": 10,
                }],
            }

        async def trend(
            _pool,
            _factory_id,
            _date_range,
            *,
            store_names,
        ):
            captured["trend_store_names"] = store_names
            return {"points": []}

        class GoldAnswer:
            def __init__(self, name):
                self.meta = {
                    "ranked_entities": [{
                        "id": name,
                        "name": name,
                        "sales_volume": 12.5,
                        "revenue": 625.0,
                        "bill_count": 10,
                    }],
                }

        async def resolver(
            _code,
            _pool,
            _factory_id,
            *,
            query,
            date_range,
            role,
        ):
            assert date_range
            assert role == "restaurant_owner"
            captured["resolver_queries"].append(query)
            return GoldAnswer("娃娃菜")

        async def external(_pool, _factory_id, _date_range):
            return {
                "dimensions": {
                    "promotion": {
                        "evidence_level": "SIMULATED",
                        "sources": [{
                            "source_code": "internal_seed_campaign",
                            "source_name": "Demo月度活动",
                        }],
                        "metrics": [{
                            "metric_code": "campaign_exposure",
                            "sum": 1000.0,
                        }],
                    },
                },
            }

        monkeypatch.setattr(se, "finance_summary", finance)
        monkeypatch.setattr(se, "daily_trend", trend)
        monkeypatch.setattr(se, "resolve_by_code", resolver)
        monkeypatch.setattr(se, "restaurant_dimension_signals", external)
        eng = _engine(monkeypatch)

        import datetime
        plan = {
            "review": True,
            "finance": True,
            "sales": True,
            "external_signals": True,
            "holiday": False,
            "supplier_anomaly": False,
            "weather": False,
            "cross": [],
        }
        fb = asyncio.run(eng._build_factbook(
            "DEMO_REST",
            (datetime.date(2026, 6, 26), datetime.date(2026, 7, 25)),
            plan,
            period=(
                "2026-06-26 至 2026-07-25；"
                "门店范围：青花椒南方百联店"
            ),
            store_names=("青花椒南方百联店",),
        ))

        assert captured["finance_store_names"] == ("青花椒南方百联店",)
        assert captured["trend_store_names"] == ("青花椒南方百联店",)
        assert all(
            "青花椒南方百联店" in query
            and "全部门店" not in query
            for query in captured["resolver_queries"]
        )
        assert len(captured["resolver_queries"]) == 2
        assert fb.finance["store_count"] == 1
        assert fb.sales["top_products"][0]["product_name"] == "娃娃菜"
        assert fb.review is None
        assert fb.external_dimensions is not None
        prompt = fb.to_prompt_text()
        assert "门店范围：青花椒南方百联店" in prompt
        assert "商圈/品牌级描述性背景" in prompt
        assert "不用全部门店数据代替" in prompt

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

    def test_period_chart_uses_actual_previous_value_as_reference_line(self, monkeypatch):
        eng = _engine(monkeypatch)
        fb = FactBook(period_comparison={
            "gross_margin_pct": {
                "current": 34.0,
                "mom_pct": 4.0,
                "mom_available": True,
            },
            "cost_ratio": {
                "current": None,
                "mom_pct": None,
                "mom_available": False,
            },
        })

        charts = eng.collect_charts(fb, {"period_comparison": True})

        margin_chart = next(c for c in charts if "加权毛利率环比" in c["title"])
        series = margin_chart["series"][0]
        assert series["data"] == [30.0, 34.0]
        assert series["markLine"]["data"] == [{"yAxis": 30.0, "name": "上期实绩"}]

    def test_period_chart_absent_when_cost_coverage_is_missing(self, monkeypatch):
        eng = _engine(monkeypatch)
        fb = FactBook(period_comparison={
            "gross_margin_pct": {
                "current": None,
                "mom_pct": None,
                "mom_available": False,
            },
        })

        charts = eng.collect_charts(fb, {"period_comparison": True})

        assert not any("毛利率" in c["title"] for c in charts)

    def test_page_dimension_hint_routes_without_context_keyword_pollution(self, monkeypatch):
        eng = _engine(monkeypatch)

        plan = eng.plan_dimensions("这个数据说明什么", dimension_hints=["finance"])

        assert plan["finance"] is True
        assert plan["review"] is False
        assert plan["sales"] is False


# --------------------------------------------------------------------------
# synthesize end-to-end (mock call_chain)
# --------------------------------------------------------------------------
class TestSynthesize:
    @pytest.mark.parametrize("factory_id", [
        "RES_3101_009",
        "qhj_prod",
        "R_GML_DEMO",
    ])
    def test_restaurant_scope_covers_supported_tenant_id_families(self, factory_id):
        assert se._is_restaurant_synthesis_tenant(factory_id) is True

    def test_factory_scope_remains_outside_restaurant_guard(self):
        assert se._is_restaurant_synthesis_tenant("F001") is False

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
        assert resp.source == "deterministic_fallback"
        assert resp.tokens == 0
        assert "叙述模型预算已用完" in resp.answer
        assert "确定性多维分析" in resp.answer
        assert resp.charts
        assert resp.dimension_coverage

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
        assert resp.source == "deterministic_fallback"
        assert "叙述模型暂时不可用" in resp.answer
        assert "平均星级" in resp.answer
        assert "营业额" in resp.answer
        assert "缺失维度保持为空" in resp.answer
        assert resp.charts
        assert resp.dimension_coverage
        # FactBook remains attached as auditable evidence.
        assert resp.factbook_text

    def test_restaurant_fallback_does_not_compare_unrelated_total_columns(
        self,
        monkeypatch,
    ):
        eng = _engine(monkeypatch)
        factbook = FactBook(
            finance={"total_revenue": 11_575_402.91},
            discount={"total_discount_amount": 1_608_809.74},
        )

        restaurant_summary = eng._analyze(
            factbook,
            period="2026-03-01 至 2026-03-31",
            restaurant_scope=True,
        )
        factory_summary = eng._analyze(
            factbook,
            period="2026-03-01 至 2026-03-31",
            restaurant_scope=False,
        )

        assert restaurant_summary == ""
        assert "总营业额占折扣金额合计" not in restaurant_summary
        # The shared factory analyzer is deliberately unchanged by this
        # restaurant-only guard.
        assert "总营业额占折扣金额合计" in factory_summary

    @pytest.mark.parametrize("unsafe_answer", [
        "峰值月主因是晚市爆发，不是天气或活动带动。",
        "3月营收主要靠晚市堂食和高价菜拉动。",
        "建议投入5000元预算，目标日均订单提升到110单。",
        "VIP顾客优先出餐。",
    ])
    def test_unsupported_causality_and_missing_dimension_claim_fall_back(
        self,
        monkeypatch,
        unsafe_answer,
    ):
        cache = FakeCache()
        eng = _engine(monkeypatch, cache=cache)
        factbook = FactBook(
            period="2026-03-01 至 2026-03-31",
            missing_dimensions=[
                missing_status("weather", reason="未接入逐日天气"),
                missing_status("promotion", reason="未接入活动成本和对照基线"),
            ],
        )

        async def fake_build(*_args, **_kwargs):
            return factbook

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            return {
                "choices": [{"message": {"content": unsafe_answer}}],
                "usage": {"total_tokens": 321},
            }

        monkeypatch.setattr(eng, "_build_factbook", fake_build)
        monkeypatch.setattr(eng, "_analyze", lambda *_args, **_kwargs: "")
        monkeypatch.setattr(se, "call_chain", fake_call_chain)

        import datetime
        dr = (datetime.date(2026, 3, 1), datetime.date(2026, 3, 31))
        resp = asyncio.run(eng.synthesize(
            "RES_3101_009",
            "结合客流、菜品、活动、天气、评价和排班分析2026-03峰值月的经营构成与可能原因",
            dr,
        ))

        assert resp.source == "deterministic_fallback"
        assert resp.tokens == 321
        assert resp.tokens_used_today == 331
        assert "叙述未通过数据因果门禁" in resp.answer
        assert unsafe_answer not in resp.answer
        assert resp.fact_check
        assert resp.fact_check["violations"]
        if "主因" in unsafe_answer or "拉动" in unsafe_answer:
            assert any(
                "无保留因果断言" in item
                for item in resp.fact_check["violations"]
            )
        if "预算" in unsafe_answer:
            assert any(
                "未标注为假设的预算或目标" in item
                for item in resp.fact_check["violations"]
            )
        if "优先出餐" in unsafe_answer:
            assert any(
                "未经验证或确认的高影响动作" in item
                for item in resp.fact_check["violations"]
            )
        if "天气" in unsafe_answer or "活动" in unsafe_answer:
            assert any(
                "缺失维度被当作事实" in item
                for item in resp.fact_check["violations"]
            )
        assert cache.put_calls == []

    def test_hedged_correlation_and_explicit_missing_disclosure_can_be_cached(self, monkeypatch):
        cache = FakeCache()
        eng = _engine(monkeypatch, cache=cache)
        factbook = FactBook(
            period="2026-03-01 至 2026-03-31",
            missing_dimensions=[
                missing_status("weather", reason="未接入逐日天气"),
                missing_status("promotion", reason="未接入活动成本和对照基线"),
            ],
        )

        async def fake_build(*_args, **_kwargs):
            return factbook

        async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
            return {
                "choices": [{"message": {"content":
                    "晚市占比较高，但只能说明经营构成，不能证明因果。"
                    "天气和营销活动数据未提供，无法判断其影响。"}}],
                "usage": {"total_tokens": 210},
            }

        async def no_capture(*_args, **_kwargs):
            return None

        monkeypatch.setattr(eng, "_build_factbook", fake_build)
        monkeypatch.setattr(eng, "_analyze", lambda *_args, **_kwargs: "")
        monkeypatch.setattr(eng, "_capture_distillation", no_capture)
        monkeypatch.setattr(se, "call_chain", fake_call_chain)

        import datetime
        dr = (datetime.date(2026, 3, 1), datetime.date(2026, 3, 31))
        resp = asyncio.run(eng.synthesize(
            "RES_3101_009",
            "分析2026-03峰值月的经营构成与可能原因",
            dr,
        ))

        assert resp.source == "llm"
        assert "不能证明因果" in resp.answer
        assert "天气和营销活动数据未提供" in resp.answer
        assert cache.put_calls

    def test_contract_version_invalidates_pre_guard_narrative_cache(self):
        assert se.SYNTHESIS_CONTRACT_VERSION == "restaurant-dimensions-v9"


class TestNarrativeGroundingGate:
    @pytest.mark.parametrize("claim", [
        "3月营收主要靠晚市堂食和高价菜拉动。",
        "晚市堂食支撑了3月营收峰值。",
        "高价菜推动营收增长。",
        "增长主要来自活动优惠。",
        "3月峰值得益于天气转好。",
        "客单价是这轮增长的关键因素。",
        "头部门店撑起了3月营收。",
        "优惠促成了订单增长。",
    ])
    def test_rejects_causal_language_family(self, claim):
        violations = se._narrative_grounding_violations(claim, FactBook())

        assert any("无保留因果断言" in item for item in violations)

    def test_allows_composition_share_without_turning_it_into_cause(self):
        assert se._narrative_grounding_violations(
            "晚市贡献54.8%的营收，这是经营构成，不能证明因果。",
            FactBook(),
        ) == []

    def test_allows_explicitly_hedged_causal_language(self):
        assert se._narrative_grounding_violations(
            "3月营收可能主要靠晚市堂食拉动，但仍待对照数据验证。",
            FactBook(),
        ) == []

    def test_rejects_unhedged_cause_and_missing_dimension_fact(self):
        factbook = FactBook(missing_dimensions=[
            missing_status("weather", reason="未接入逐日天气"),
            missing_status("staffing", reason="未接入逐时在岗人数"),
        ])

        violations = se._narrative_grounding_violations(
            "主因是晚市爆发，不是天气带动。高峰排班充足。",
            factbook,
        )

        assert any("无保留因果断言" in item for item in violations)
        assert any("weather" in item for item in violations)
        assert any("staffing" in item for item in violations)

    def test_allows_deterministic_attribution_and_missing_disclosure(self):
        factbook = FactBook(
            attribution={"primary_cause": "客单价"},
            missing_dimensions=[
                missing_status("weather", reason="未接入逐日天气"),
            ],
        )

        assert se._narrative_grounding_violations(
            "这家店拖后腿的主要原因是客单价。天气数据未提供，无法判断其影响。",
            factbook,
        ) == []

    def test_attribution_fact_does_not_exempt_a_different_cause(self):
        factbook = FactBook(attribution={"primary_cause": "客单价"})

        violations = se._narrative_grounding_violations(
            "客单价是主要原因，天气又造成了增长。",
            factbook,
        )

        assert any("无保留因果断言" in item for item in violations)

    def test_one_missing_disclosure_does_not_exempt_another_clause(self):
        factbook = FactBook(missing_dimensions=[
            missing_status("weather", reason="未接入逐日天气"),
            missing_status("promotion", reason="未接入活动成本和对照基线"),
        ])

        violations = se._narrative_grounding_violations(
            "天气数据未提供，但活动带动了晚市增长。",
            factbook,
        )

        assert not any("weather" in item for item in violations)
        assert any("promotion" in item for item in violations)

    def test_rejects_unlabelled_budget_and_kpi_targets(self):
        violations = se._narrative_grounding_violations(
            "建议投入5000元预算，目标日均订单提升到110单。",
            FactBook(),
        )

        assert any("未标注为假设的预算或目标" in item for item in violations)

    def test_allows_adjustable_budget_and_target_pending_owner_confirmation(self):
        assert se._narrative_grounding_violations(
            "试点参数（建议值，需老板确认）：预算5000元，目标日均订单110单。",
            FactBook(),
        ) == []

    def test_user_supplied_budget_is_not_treated_as_model_fabrication(self):
        assert se._narrative_grounding_violations(
            "按预算5000元做小范围试点，确认后再执行。",
            FactBook(),
            "我只有预算5000元，应该怎么测试？",
        ) == []

    def test_rejects_unapproved_high_impact_action(self):
        violations = se._narrative_grounding_violations(
            "VIP顾客优先出餐。",
            FactBook(),
        )

        assert any("未经验证或确认的高影响动作" in item for item in violations)

    def test_allows_high_impact_action_only_as_confirmed_reversible_experiment(self):
        assert se._narrative_grounding_violations(
            "先小范围试点VIP优先出餐，需门店确认后执行。",
            FactBook(),
        ) == []

    def test_allows_observed_discount_fact_without_treating_it_as_an_action(self):
        assert se._narrative_grounding_violations(
            "本月满减金额500元，占营业额5%。",
            FactBook(),
        ) == []

    def test_observed_discount_exception_does_not_authorize_new_promotion(self):
        violations = se._narrative_grounding_violations(
            "建议满减10元。",
            FactBook(),
        )
        assert any("高影响动作" in item for item in violations)

    def test_factbook_exposes_both_high_and_low_dish_sales_candidates(self):
        factbook = FactBook(sales={
            "top_products": [{
                "product_name": "招牌鱼",
                "revenue": 3000,
                "qty_sold": 40,
                "bill_count": 30,
            }],
            "bottom_products": [{
                "product_name": "冷门菜",
                "revenue": 30,
                "qty_sold": 2,
                "bill_count": 2,
            }],
        })

        prompt = factbook.to_prompt_text()
        facts = factbook.to_facts_index()
        assert "高营业额菜品" in prompt
        assert "低营业额菜品候选" in prompt
        assert "冷门菜" in prompt
        assert facts["低营业额冷门菜菜品销量"] == 2


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
        resp = asyncio.run(eng.synthesize("RES_3101_009", "哪家店拖后腿", dr))
        # P4: pure-attribution routes to the thin-restate path — which must ALSO
        # capture to the corpus (shared _capture_distillation helper).
        assert resp.source == "thin_restate"
        assert len(captured) == 1
        c = captured[0]
        assert c["source"] == "synthesis"
        assert c["task_type"] == "synthesis"
        assert c["factory_id"] == "RES_3101_009"
        assert c["business_type"] == "restaurant"
        # answer captured verbatim (grounded output)
        assert c["teacher_output"] == resp.answer
        # input_text embeds the question + data context (teaches FROM data)
        assert "哪家店拖后腿" in c["input_text"]
        assert "数据上下文" in c["input_text"]
        # metadata carries the demand signal: raw query + family classification
        assert c["metadata"]["query"] == "哪家店拖后腿"
        assert c["metadata"]["question_family"] == "attribution"
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
        assert resp.source == "deterministic_fallback"
        assert captured == []  # deterministic fallback is not an LLM teacher sample

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
        assert resp.source == "deterministic_fallback"
        assert captured == []  # no model output exists to use as a teacher sample

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
