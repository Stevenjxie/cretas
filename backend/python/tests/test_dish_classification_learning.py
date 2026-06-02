"""Dish-classification self-learning tests.

Part 1 — classify_dish consult precedence (static dict → graduated → '其他'):
  * an unknown dish that has GRADUATED resolves via consult (not '其他')
  * a static-dict dish still returns the static category (consult NOT consulted)
  * a consult that raises does not break classify_dish (falls to '其他')

Part 2 — async LLM enrichment + capture:
  * only '其他' dishes are sent to the LLM
  * out-of-set / '其他' LLM answers are skipped
  * capture_candidate called per valid dish with
    learning_type='classification' / business_type='restaurant' / method='llm'
  * an LLM or pool failure does not raise (fire-and-forget)

Everything is mocked — NO real DB / LLM. pytest-asyncio asyncio_mode=auto.
Reference: tests/test_learning_promotion.py.
"""
from __future__ import annotations

import smartbi.services.materialized_analytics.restaurant.dish_classifier as dc
import smartbi.services.materialized_analytics.restaurant.dish_classifier_learning as dcl


# ---------------------------------------------------------------------------
# Part 1 — classify_dish consult precedence
# ---------------------------------------------------------------------------

def test_consult_resolves_graduated_unknown(monkeypatch):
    """A dish the static dict misses but that GRADUATED → consult result."""
    called = {}

    def fake_consult(learning_type, source_key, business_type=None):
        called["args"] = (learning_type, source_key, business_type)
        return ("配菜", "promoted_industry")

    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted", fake_consult
    )
    # 某不在关键词字典里的菜名 — static dict misses → consult fires.
    assert dc.classify_dish("某个看不出是啥的菜") == "配菜"
    assert called["args"] == ("classification", "某个看不出是啥的菜", "restaurant")


def test_static_dict_wins_consult_not_consulted(monkeypatch):
    """A static-dict match returns the static category; consult is NOT called."""
    def boom_consult(*a, **k):
        raise AssertionError("consult_promoted must NOT be called on a static hit")

    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted", boom_consult
    )
    # 可口可乐 → 饮品 via static dict; consult must not run.
    assert dc.classify_dish("可口可乐") == "饮品"
    # 百威啤酒 → 啤酒 via static dict.
    assert dc.classify_dish("百威啤酒") == "啤酒"


def test_consult_raises_falls_to_qita(monkeypatch):
    """consult raising must NOT break classify_dish — falls through to '其他'."""
    def raising_consult(*a, **k):
        raise RuntimeError("promoted file corrupt")

    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted", raising_consult
    )
    assert dc.classify_dish("某个看不出是啥的菜") == "其他"


def test_consult_miss_returns_qita(monkeypatch):
    """consult returning (None, None) → '其他' (no graduated rule)."""
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda *a, **k: (None, None),
    )
    assert dc.classify_dish("某个看不出是啥的菜") == "其他"


# ---------------------------------------------------------------------------
# Part 2 — async LLM enrichment + capture
# ---------------------------------------------------------------------------

class _FakePool:
    """asyncpg-pool stand-in; never actually used (capture is mocked)."""


async def _patch_capture(monkeypatch):
    """Replace capture_candidate; return the list it records calls into."""
    calls = []

    async def fake_capture(pool, learning_type, source_key, target_value,
                           factory_id, method, confidence, business_type="unknown"):
        calls.append(dict(
            learning_type=learning_type, source_key=source_key,
            target_value=target_value, factory_id=factory_id, method=method,
            confidence=confidence, business_type=business_type,
        ))

    monkeypatch.setattr(
        "smartbi.services.learning_promotion.capture_candidate", fake_capture
    )
    return calls


def _patch_llm(monkeypatch, mapping):
    """Replace llm_client.call_llm to return a JSON dish→category map."""
    import json

    async def fake_call_llm(prompt, system_role=None, **kwargs):
        # Record the prompt so the test can assert which dishes were sent.
        fake_call_llm.last_prompt = prompt
        return json.dumps(mapping, ensure_ascii=False)

    fake_call_llm.last_prompt = None
    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", fake_call_llm
    )
    return fake_call_llm


async def test_enrich_captures_valid_dishes(monkeypatch):
    """Valid (in-set) classifications are captured with the right metadata."""
    calls = await _patch_capture(monkeypatch)
    # LLM maps two unknowns to valid categories + one to '其他' (skip) +
    # one out-of-set fictional category (skip).
    fake_llm = _patch_llm(monkeypatch, {
        "神秘菜A": "配菜",
        "神秘菜B": "主食",
        "神秘菜C": "其他",          # default — must NOT be captured
        "神秘菜D": "不存在的类别",    # out-of-set — must NOT be captured
    })

    captured = await dcl.enrich_unknown_dishes(
        _FakePool(), "RES_X", ["神秘菜A", "神秘菜B", "神秘菜C", "神秘菜D"]
    )

    assert captured == 2
    by_dish = {c["source_key"]: c for c in calls}
    assert set(by_dish) == {"神秘菜A", "神秘菜B"}
    for c in calls:
        assert c["learning_type"] == "classification"
        assert c["business_type"] == "restaurant"
        assert c["method"] == "llm"
        assert c["factory_id"] == "RES_X"
        assert 0.0 < c["confidence"] <= 1.0
    assert by_dish["神秘菜A"]["target_value"] == "配菜"
    assert by_dish["神秘菜B"]["target_value"] == "主食"
    # All requested dishes appeared in the prompt.
    for n in ("神秘菜A", "神秘菜B", "神秘菜C", "神秘菜D"):
        assert n in fake_llm.last_prompt


async def test_enrich_skips_dish_not_requested(monkeypatch):
    """An LLM answer for a dish we did NOT ask about is ignored."""
    calls = await _patch_capture(monkeypatch)
    _patch_llm(monkeypatch, {"没问过的菜": "配菜"})

    captured = await dcl.enrich_unknown_dishes(_FakePool(), "RES_X", ["神秘菜A"])
    assert captured == 0
    assert calls == []


async def test_enrich_empty_input_no_llm(monkeypatch):
    """Zero unknowns → no LLM call, no captures, returns 0."""
    calls = await _patch_capture(monkeypatch)

    async def boom_llm(*a, **k):
        raise AssertionError("LLM must NOT be called with zero unknown dishes")

    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", boom_llm
    )
    captured = await dcl.enrich_unknown_dishes(_FakePool(), "RES_X", [])
    assert captured == 0
    assert calls == []


async def test_enrich_llm_failure_does_not_raise(monkeypatch):
    """An LLM exception is swallowed (fire-and-forget) → returns 0."""
    await _patch_capture(monkeypatch)

    async def raising_llm(*a, **k):
        raise RuntimeError("provider down")

    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", raising_llm
    )
    captured = await dcl.enrich_unknown_dishes(_FakePool(), "RES_X", ["神秘菜A"])
    assert captured == 0


async def test_enrich_capture_failure_does_not_raise(monkeypatch):
    """A capture/pool failure on one dish does not raise; others still counted."""
    _patch_llm(monkeypatch, {"神秘菜A": "配菜", "神秘菜B": "主食"})

    seen = []

    async def flaky_capture(pool, learning_type, source_key, *a, **k):
        seen.append(source_key)
        if source_key == "神秘菜A":
            raise RuntimeError("pool acquire failed")

    monkeypatch.setattr(
        "smartbi.services.learning_promotion.capture_candidate", flaky_capture
    )
    captured = await dcl.enrich_unknown_dishes(
        _FakePool(), "RES_X", ["神秘菜A", "神秘菜B"]
    )
    # 神秘菜A raised (not counted); 神秘菜B captured.
    assert captured == 1
    assert set(seen) == {"神秘菜A", "神秘菜B"}


async def test_enrich_truncates_over_cap(monkeypatch):
    """More than the cap of distinct unknowns → only the cap is sent."""
    await _patch_capture(monkeypatch)
    fake_llm = _patch_llm(monkeypatch, {})  # empty map → 0 captured, just check prompt

    over = [f"菜{i:04d}" for i in range(dcl._MAX_UNKNOWN_DISHES + 25)]
    await dcl.enrich_unknown_dishes(_FakePool(), "RES_X", over)
    # The dish just past the cap must NOT be in the prompt; one within is.
    assert over[0] in fake_llm.last_prompt
    assert over[dcl._MAX_UNKNOWN_DISHES] not in fake_llm.last_prompt


# ---------------------------------------------------------------------------
# collect_unknown_dishes + orchestrator gating
# ---------------------------------------------------------------------------

class _FakeDF:
    def __init__(self, column_name, values):
        self._col = column_name
        self._values = values

    @property
    def columns(self):
        return [self._col]

    def get_column(self, name):
        assert name == self._col
        return self

    def head(self, _n):
        return self

    def to_list(self):
        return list(self._values)


class _FakeBackend:
    def __init__(self, df):
        self._df = df


class _FakeSchema:
    def __init__(self, domain_value, field_names=("商品信息",)):
        self.domain = type("D", (), {"value": domain_value})()
        self.fields = [type("F", (), {"name": n})() for n in field_names]


def test_collect_unknown_dishes_only_qita(monkeypatch):
    """Only dishes that classify to '其他' (static + consult miss) are returned."""
    # consult always misses → only static-dict-miss dishes are unknown.
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda *a, **k: (None, None),
    )
    # 可口可乐→饮品 (known), 神秘冷盘→其他 (unknown), 米饭→system (filtered by parser)
    df = _FakeDF("商品信息", ["可口可乐_1听*6+神秘冷盘_1份*18"])
    backend = _FakeBackend(df)
    unknown = dcl.collect_unknown_dishes(backend, _FakeSchema("restaurant"))
    assert unknown == ["神秘冷盘"]


def test_collect_unknown_dishes_no_item_column():
    """No 商品信息 column → no unknowns (not a restaurant item upload)."""
    df = _FakeDF("销售额", [100, 200])
    backend = _FakeBackend(df)
    assert dcl.collect_unknown_dishes(backend, _FakeSchema("restaurant")) == []


async def test_orchestrator_skips_no_dish_column(monkeypatch):
    """No 商品信息 column in schema → orchestrator returns 0, no LLM call.
    (Gate is the dish column, mirroring dish_category_breakdown.applies.)"""
    async def boom_llm(*a, **k):
        raise AssertionError("must not enrich an upload with no dish column")

    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", boom_llm
    )
    df = _FakeDF("销售额", [100, 200])
    captured = await dcl.maybe_enrich_dish_classifications(
        _FakePool(), "F001", _FakeBackend(df), _FakeSchema("finance", field_names=("销售额",))
    )
    assert captured == 0


async def test_orchestrator_enriches_unknown_domain_with_dishes(monkeypatch):
    """The fix: an upload with a 商品信息 column enriches EVEN when domain
    detects 'unknown' (restaurant POS data often does). Domain no longer gates."""
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda *a, **k: (None, None),
    )
    calls = await _patch_capture(monkeypatch)
    _patch_llm(monkeypatch, {"神秘冷盘": "配菜"})

    df = _FakeDF("商品信息", ["神秘冷盘_1份*18"])
    captured = await dcl.maybe_enrich_dish_classifications(
        _FakePool(), "RES_X", _FakeBackend(df), _FakeSchema("unknown")
    )
    assert captured == 1
    assert calls[0]["source_key"] == "神秘冷盘"


async def test_orchestrator_restaurant_enriches(monkeypatch):
    """Restaurant upload with an unknown dish → enrich + capture runs."""
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda *a, **k: (None, None),
    )
    calls = await _patch_capture(monkeypatch)
    _patch_llm(monkeypatch, {"神秘冷盘": "配菜"})

    df = _FakeDF("商品信息", ["神秘冷盘_1份*18"])
    captured = await dcl.maybe_enrich_dish_classifications(
        _FakePool(), "RES_X", _FakeBackend(df), _FakeSchema("restaurant")
    )
    assert captured == 1
    assert calls[0]["source_key"] == "神秘冷盘"
    assert calls[0]["target_value"] == "配菜"
    assert calls[0]["learning_type"] == "classification"
    assert calls[0]["business_type"] == "restaurant"


async def test_orchestrator_never_raises(monkeypatch):
    """A failure deep inside enrichment is swallowed by the orchestrator."""
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda *a, **k: (None, None),
    )

    async def raising_llm(*a, **k):
        raise RuntimeError("kaboom")

    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", raising_llm
    )
    df = _FakeDF("商品信息", ["神秘冷盘_1份*18"])
    captured = await dcl.maybe_enrich_dish_classifications(
        _FakePool(), "RES_X", _FakeBackend(df), _FakeSchema("restaurant")
    )
    assert captured == 0
