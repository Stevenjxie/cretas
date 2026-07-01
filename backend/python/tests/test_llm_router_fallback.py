"""Tests for the smart LLM router (2026-07-01 rebuild).

Spec: docs/superpowers/specs/2026-07-01-smart-llm-router-spec.md (6-agent audited).
Architecture: billing-safe per-(account,model) registry (Layer 1) + capability chains
(Layer 2) + per-slot param profiles (Layer 3) + outcome validation & 429/403 split
(Layer 4) + attribution (Layer 5, in llm_metrics).

The CENTRAL test is the billing-safety invariant (design-audit must-have c): every
model any SLOT chain could call is a confirmed-ON registry entry, and the registry
shares zero members with the per-account landmine set (the models that BILL). This
locks the money-path that caused the 6/11 silent-billing incident.
"""
from __future__ import annotations

import datetime
from typing import Dict, List, Tuple
from unittest.mock import MagicMock

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain


# Per-account landmines — models that BILL if called (未开启 / 不支持开启 on that
# account, per console scrape 2026-06-30). NONE may ever be in _SAFE_MODELS.
_LANDMINES = {
    ("aliyun_b", "kimi-k2.7-code"),   # 未开启 on b (ON on a/c)
    ("aliyun_b", "qwen3.5-ocr"),      # 未开启 on b (ON on c)
    ("aliyun_b", "deepseek-v4-pro"),  # 不支持开启 on b (ON on c)
    ("aliyun_b", "glm-5.2"),          # 不支持开启 on b (ON on c)
    ("aliyun_c", "deepseek-v4-pro"),  # only free on tencent; ON-but-check per acct
}


@pytest.fixture(autouse=True)
def _reset_caches():
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


# ════════════════════════════════════════════════════════════════════════
# Layer 1 — billing-safety invariants (THE money-path net)
# ════════════════════════════════════════════════════════════════════════

def test_every_chain_entry_is_a_registered_safe_model():
    """Nothing a SLOT chain can call is outside _SAFE_MODELS (ON-toggle allowlist)."""
    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            assert (account, model) in llm_router._SAFE_MODELS, (
                f"{slot.value}: {account}/{model} not in _SAFE_MODELS → could bill"
            )


def test_registry_shares_no_members_with_landmines():
    """The ON allowlist must never contain a per-account BILLS model."""
    hits = [p for p in _LANDMINES if p in llm_router._SAFE_MODELS]
    # deepseek-v4-pro is only registered on tencent; assert the aliyun landmines absent.
    aliyun_hits = [p for p in hits if p[0].startswith("aliyun")]
    assert not aliyun_hits, f"landmines in registry → would bill: {aliyun_hits}"


def test_no_thinking_only_model_in_fast_slots():
    """thinking-only models (always reason, slow) belong to REASONING only."""
    fast = (SLOT.CHAT, SLOT.CHART, SLOT.MAPPER)
    for slot in fast:
        for _ac, m in llm_router.SLOT_MODELS[slot]:
            assert m not in llm_router._THINKING_ONLY, f"{slot.value} has thinking-only {m}"


def test_no_denylist_name_in_registry():
    for account, model in llm_router._SAFE_MODELS:
        assert model not in llm_router._PAID_MODEL_DENYLIST, (
            f"denylisted {model} on {account} in registry"
        )


def test_chains_deduped():
    for slot, chain in llm_router.SLOT_MODELS.items():
        assert len(chain) == len(set(chain)), f"{slot.value} chain has duplicates"


def test_vl_chain_is_vision_only():
    for account, model in llm_router.SLOT_MODELS[SLOT.VL]:
        assert ("vl" in model) or (model == "glm-4.6v"), f"VL non-vision {account}/{model}"


# ════════════════════════════════════════════════════════════════════════
# _refuse_reason — the single shared billing gate
# ════════════════════════════════════════════════════════════════════════

_TODAY = datetime.date(2026, 7, 1)  # registry audit date → not stale


def test_refuse_allows_current_registered_model():
    assert llm_router._refuse_reason("aliyun_c", "qwen3.7-max-2026-06-08", _TODAY) is None


def test_refuse_rejects_unregistered_landmine():
    # config-drift guard: even if someone put a landmine in a chain, the gate refuses.
    assert llm_router._refuse_reason("aliyun_b", "kimi-k2.7-code", _TODAY) == "not_allowlisted"
    assert llm_router._refuse_reason("aliyun_b", "deepseek-v4-pro", _TODAY) == "not_allowlisted"


def test_refuse_hard_drops_expired():
    # aliyun_a/qwen3.6-plus-2026-04-02 expires 07/02 — refused the day it lapses.
    after = datetime.date(2026, 7, 3)
    assert llm_router._refuse_reason("aliyun_a", "qwen3.6-plus-2026-04-02", after) == "expired"


def test_refuse_denylist_veto():
    # Inject a denylisted name into the registry → still vetoed (belt & suspenders).
    key = ("aliyun_c", "qwen-max")
    llm_router._SAFE_MODELS[key] = datetime.date(2026, 9, 1)
    try:
        assert llm_router._refuse_reason("aliyun_c", "qwen-max", _TODAY) == "paid_denylist"
    finally:
        del llm_router._SAFE_MODELS[key]


def test_staleness_failsafe_narrows_to_minimal_set():
    stale = _TODAY + datetime.timedelta(days=llm_router._REGISTRY_MAX_AGE_DAYS + 1)
    # a normal registered model NOT in the minimal set is refused when stale…
    assert llm_router._refuse_reason("aliyun_b", "glm-5", stale) == "registry_stale"
    # …but a minimal-set survivor still allowed (if not itself expired).
    assert llm_router._refuse_reason("aliyun_c", "glm-5.2", stale) is None


def test_future_date_every_slot_keeps_a_live_fallback():
    """After B+C bulk expiry (08/14) every slot still resolves ≥1 model (minimal set
    + never-expiring tencent/zhipu floor) — no hard 'all exhausted' (design-audit R3)."""
    future = datetime.date(2026, 8, 14)
    for slot, chain in llm_router.SLOT_MODELS.items():
        live = [(a, m) for (a, m) in chain if llm_router._refuse_reason(a, m, future) is None]
        assert live, f"{slot.value} has ZERO live fallbacks at {future}"


# ════════════════════════════════════════════════════════════════════════
# Layer 3 — per-slot param profiles
# ════════════════════════════════════════════════════════════════════════

def test_fast_slots_disable_thinking_on_aliyun_hybrid():
    p = llm_router._apply_slot_params(SLOT.CHAT, "aliyun_c", "qwen3.5-flash",
                                      {"messages": [{"role": "user", "content": "hi"}]})
    assert p["enable_thinking"] is False


def test_reasoning_does_not_force_enable_thinking():
    # deepseek-v3.1 400s on enable_thinking=true (only supports false/absent); forced
    # deep thinking also times out call_chain. REASONING must NOT inject enable_thinking.
    p = llm_router._apply_slot_params(SLOT.REASONING, "aliyun_c", "deepseek-v3.1", {"messages": []})
    assert "enable_thinking" not in p


def test_thinking_only_model_gets_no_enable_thinking():
    p = llm_router._apply_slot_params(SLOT.REASONING, "aliyun_c", "deepseek-r1", {"messages": []})
    assert "enable_thinking" not in p


def test_non_aliyun_provider_gets_no_enable_thinking():
    p = llm_router._apply_slot_params(SLOT.CHAT, "tencent", "qwen3.5-flash", {"messages": []})
    assert "enable_thinking" not in p


def test_chart_json_object_only_when_prompt_mentions_json():
    with_json = llm_router._apply_slot_params(
        SLOT.CHART, "aliyun_c", "qwen3.5-flash",
        {"messages": [{"role": "system", "content": "只返回 JSON"}], "max_tokens": 150})
    assert with_json["response_format"] == {"type": "json_object"}
    assert "max_tokens" not in with_json          # popped (truncation = parse fail)
    assert with_json["temperature"] == 0
    without = llm_router._apply_slot_params(
        SLOT.CHART, "aliyun_c", "qwen3.5-flash",
        {"messages": [{"role": "system", "content": "recommend a chart"}]})
    assert "response_format" not in without        # avoid the 400


# ════════════════════════════════════════════════════════════════════════
# Layer 4 — outcome validation + 429/403 split
# ════════════════════════════════════════════════════════════════════════

def test_validate_empty_is_invalid():
    assert llm_router._validate_output(SLOT.CHAT, "") == "empty"
    assert llm_router._validate_output(SLOT.CHAT, "   ") == "empty"


def test_validate_chart_json():
    assert llm_router._validate_output(SLOT.CHART, "not json at all") == "bad_json"
    assert llm_router._validate_output(SLOT.CHART, '{"chartType":"BAR"}') is None
    assert llm_router._validate_output(SLOT.CHART, '```json\n{"a":1}\n```') is None


def test_validate_insights_min_length():
    assert llm_router._validate_output(SLOT.INSIGHTS, "ok") == "too_short"
    assert llm_router._validate_output(SLOT.INSIGHTS, "销售增长由复购率提升驱动，忠诚度增强。") is None


def test_429_is_not_quota_exhausted():
    # The correctness fix: transient 429 must NOT trigger the 6h quota-skip.
    assert llm_router._is_quota_exhausted(429, "Too Many Requests") is False
    assert llm_router._is_quota_exhausted(429, "") is False


def test_403_and_402_are_quota_exhausted():
    assert llm_router._is_quota_exhausted(403, "AllocationQuota.FreeTierOnly") is True
    assert llm_router._is_quota_exhausted(402, "Insufficient Balance") is True
    assert llm_router._is_quota_exhausted(402, "endpoint inactive: FREE_QUOTA_EXHAUSTED") is True
    assert llm_router._is_quota_exhausted(500, "FreeTierOnly") is False


# ════════════════════════════════════════════════════════════════════════
# call_chain fallback smoke (mocked HTTP) — every attempted model is billing-safe
# ════════════════════════════════════════════════════════════════════════

def _fake_response(status_code: int, body: str = "", json_payload=None):
    resp = MagicMock()
    resp.status_code = status_code
    resp.text = body
    resp.json = MagicMock(return_value=json_payload or {})
    return resp


class _ScriptedClient:
    """httpx-AsyncClient stand-in returning a canned response per ACCOUNT (keyed by
    api-key substring). Records every (account, model) tried."""

    def __init__(self, by_account: Dict[str, MagicMock]):
        self.by_account = by_account
        self.call_log: List[Tuple[str, str]] = []

    async def post(self, url, headers=None, json=None, timeout=None):
        model = (json or {}).get("model", "?")
        auth = (headers or {}).get("Authorization", "")
        account = None
        if "dashscope" in url or "aliyuncs" in url:
            if "key_c" in auth:
                account = "aliyun_c"
            elif "key_b" in auth:
                account = "aliyun_b"
            elif "key_a" in auth:
                account = "aliyun_a"
        elif "tokenhub" in url:
            account = "tencent"
        elif "bigmodel" in url:
            account = "zhipu"
        self.call_log.append((account or "unknown", model))
        return self.by_account.get(account, _fake_response(500, "no canned response"))


def _patch_keys(monkeypatch):
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "key_a_fake")
    monkeypatch.setenv("LLM_ALIYUN_B_API_KEY", "key_b_fake")
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "key_c_fake")
    monkeypatch.setenv("LLM_TENCENT_API_KEY", "key_tencent_fake")
    monkeypatch.setenv("LLM_ZHIPU_API_KEY", "key_zhipu_fake")


@pytest.mark.asyncio
async def test_call_chain_falls_through_403_to_next_and_only_calls_safe_models(monkeypatch):
    _patch_keys(monkeypatch)
    ok = {"choices": [{"message": {"content": "今日入库3批，均合格。"}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(403, "AllocationQuota.FreeTierOnly"),
        "aliyun_b": _fake_response(200, json_payload=ok),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})
    assert result == ok
    # every model actually attempted must be a registered safe model on its account
    for account, model in client.call_log:
        assert (account, model) in llm_router._SAFE_MODELS, f"unsafe call {account}/{model}"


@pytest.mark.asyncio
async def test_call_chain_rejects_empty_body_and_falls_back(monkeypatch):
    _patch_keys(monkeypatch)
    empty = {"choices": [{"message": {"content": ""}}]}
    good = {"choices": [{"message": {"content": "今日入库3批，均合格。"}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=empty),   # 200 but garbage
        "aliyun_b": _fake_response(200, json_payload=good),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})
    assert result == good                                      # skipped the empty 200
    assert len(client.call_log) >= 2                           # did not stop at empty
