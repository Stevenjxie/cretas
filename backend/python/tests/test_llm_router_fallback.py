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
import asyncio
import time
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


def test_non_thinking_profile_slots_exclude_force_thinking_models():
    """Console quota does not make a model protocol-compatible.

    Live A/B/C probes showed the 05-17 and preview Max SKUs reject
    enable_thinking=false. Any slot that injects false must exclude them.
    """
    force_thinking = {
        "qwen3.7-max-2026-05-17",
        "qwen3.7-max-preview",
    }
    for slot, profile in llm_router._SLOT_PARAMS.items():
        if profile.get("enable_thinking") is False:
            models = {model for _account, model in llm_router.SLOT_MODELS[slot]}
            assert models.isdisjoint(force_thinking), (
                f"{slot.value} would send enable_thinking=false to "
                f"{sorted(models & force_thinking)}"
            )


def test_no_denylist_name_in_registry():
    for account, model in llm_router._SAFE_MODELS:
        assert model not in llm_router._PAID_MODEL_DENYLIST, (
            f"denylisted {model} on {account} in registry"
        )


def test_chains_deduped():
    for slot, chain in llm_router.SLOT_MODELS.items():
        assert len(chain) == len(set(chain)), f"{slot.value} chain has duplicates"


def test_mapper_uses_bounded_fast_models_without_max_or_reasoners():
    chain = llm_router.SLOT_MODELS[SLOT.MAPPER]
    assert chain[:4] == [
        ("aliyun_c", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "qwen3.7-flash-2026-07-15"),
        ("aliyun_c", "qwen3.7-flash"),
        ("aliyun_b", "qwen3.7-flash"),
    ]
    assert ("aliyun_c", "glm-5.2") in chain
    assert ("aliyun_c", "qwen3.7-plus") in chain
    assert ("aliyun_b", "qwen3.7-plus") in chain
    assert ("aliyun_a", "qwen3.7-plus") in chain
    assert ("zhipu", "glm-4.5-air") in chain
    assert all(
        token not in model
        for _account, model in chain
        for token in ("max", "deepseek", "kimi")
    )


def test_insights_prefers_interleaved_free_plus_before_max_deep_tail():
    chain = llm_router.SLOT_MODELS[SLOT.INSIGHTS]
    assert chain[:6] == [
        ("aliyun_c", "qwen3.7-plus"),
        ("aliyun_b", "qwen3.7-plus"),
        ("aliyun_a", "qwen3.7-plus"),
        ("aliyun_c", "qwen3.7-plus-2026-05-26"),
        ("aliyun_b", "qwen3.7-plus-2026-05-26"),
        ("aliyun_a", "qwen3.7-plus-2026-05-26"),
    ]
    first_max = next(i for i, (_account, model) in enumerate(chain) if "max" in model)
    assert first_max >= 8
    assert all(model not in llm_router._THINKING_ONLY for _account, model in chain[:8])
    assert ("aliyun_a", "qwen3.7-max-2026-05-20") not in chain
    assert ("aliyun_a", "qwen3.7-max-2026-06-08") in chain


def test_review_uses_verified_non_thinking_abc_fallbacks():
    chain = llm_router.SLOT_MODELS[SLOT.REVIEW]
    assert chain[:5] == [
        ("aliyun_a", "qwen3.7-max-2026-06-08"),
        ("aliyun_c", "qwen3.7-max-2026-06-08"),
        ("aliyun_c", "qwen3.7-plus"),
        ("aliyun_b", "qwen3.7-plus"),
        ("aliyun_a", "qwen3.7-plus"),
    ]
    assert all(model not in llm_router._THINKING_ONLY for _account, model in chain)


def test_new_b_and_c_flash_quota_pairs_are_registered_and_head_fast_slots():
    expected = [
        ("aliyun_c", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "qwen3.7-flash-2026-07-15"),
        ("aliyun_c", "qwen3.7-flash"),
        ("aliyun_b", "qwen3.7-flash"),
    ]
    for pair in expected:
        assert llm_router._SAFE_MODELS[pair] == datetime.date(2026, 10, 23)
        assert pair in llm_router._MINIMAL_SAFE_SET
    for slot in (SLOT.CHAT, SLOT.CHART, SLOT.MAPPER):
        assert llm_router.SLOT_MODELS[slot][:4] == expected


def test_vl_chain_is_vision_only():
    for account, model in llm_router.SLOT_MODELS[SLOT.VL]:
        assert ("vl" in model) or (model == "glm-4.6v"), f"VL non-vision {account}/{model}"


# ════════════════════════════════════════════════════════════════════════
# _refuse_reason — the single shared billing gate
# ════════════════════════════════════════════════════════════════════════

_TODAY = datetime.date(2026, 7, 26)  # registry audit date → not stale
# (与 llm_router._REGISTRY_AUDIT_DATE 同步更新; call_chain 类测试用
# monkeypatch llm_router._today 冻结, 不再随真实日期漂移碎裂)


def test_refuse_allows_current_registered_model():
    assert llm_router._refuse_reason("aliyun_c", "qwen3.7-max-2026-06-08", _TODAY) is None
    assert llm_router._refuse_reason(
        "aliyun_c", "qwen3.7-flash-2026-07-15", _TODAY
    ) is None
    assert llm_router._refuse_reason(
        "aliyun_b", "qwen3.7-flash-2026-07-15", _TODAY
    ) is None


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
    # (模型须在 stale 日期时未过期, 否则 'expired' 先命中 — 选 09/01 的 plus)
    assert llm_router._refuse_reason("aliyun_c", "qwen3.7-plus", stale) == "registry_stale"
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
    assert llm_router._is_quota_exhausted(
        402,
        '{"error":{"code":"401008","message":"The free trial quota for the service '
        'has been exhausted and postpaid billing is not enabled"}}',
    ) is True
    assert llm_router._is_quota_exhausted(402, '{"error":{"code":401008}}') is True
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
    # 冻结在 07-10: 脚本前提是 a 头(qwen3.6-flash, 到期07-17)与 b 层(07-16批)
    # 都活着; _TODAY(=审计日 07-23)时它们已过期, 不能用。
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 7, 10))
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
    # 冻结在 07-10: 脚本前提是 a 头(qwen3.6-flash, 到期07-17)与 b 层(07-16批)
    # 都活着; _TODAY(=审计日 07-23)时它们已过期, 不能用。
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 7, 10))
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


@pytest.mark.asyncio
async def test_call_chain_total_timeout_caps_the_whole_provider_cascade(monkeypatch):
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 7, 10))

    class _SlowClient(_ScriptedClient):
        async def post(self, url, headers=None, json=None, timeout=None):
            await asyncio.sleep(1)
            return await super().post(url, headers=headers, json=json, timeout=timeout)

    client = _SlowClient({})
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    started = time.monotonic()

    with pytest.raises(RuntimeError, match="total_timeout|timed out"):
        await call_chain(
            SLOT.CHAT,
            {"messages": [{"role": "user", "content": "hi"}]},
            timeout=0.2,
            total_timeout=0.35,
        )

    assert time.monotonic() - started < 0.8


# ════════════════════════════════════════════════════════════════════════
# 2026-07-30 evening — non-Aliyun floor: reachability, live TokenHub IDs,
# per-model payload constraints, and the caller-supplied content gate.
#
# What these lock down (all measured against prod keys on the real
# restaurant-T3 prompt, 5 diverse questions, production max_tokens=500):
#   * The floor was UNREACHABLE, not merely thin: aliyun_c/deepseek-v3.2
#     answers with confidence -0.95, which is a 200 as far as the router is
#     concerned, so the cascade stopped there and never reached _TEXT_TAIL.
#   * Three TokenHub IDs in the chain are dead (console: 已停止 / 余额 0) and
#     three live ones holding ~2.5M free tokens were never wired.
#   * TokenHub enforces per-model sampling rules the OpenAI-compatible schema
#     cannot express, and the router's normalizer was a passthrough.
# ════════════════════════════════════════════════════════════════════════

# Console scrape 2026-07-30 (状态=已停止, 免费额度余量=0). Calling these can only
# burn a request and then park the (account,model) in the 6h quota-skip cache.
_TOKENHUB_EXHAUSTED = {
    ("tencent", "qwen3.5-flash"),
    ("tencent", "glm-5.1"),
    ("tencent", "deepseek-v4-flash"),
}

# 5/5 on the real T3 prompt at production max_tokens=500.
_TOKENHUB_VERIFIED = {
    ("tencent", "hy-mt2-pro"),
    ("tencent", "deepseek-v3.1-terminus"),
    ("tencent", "qwen3.5-plus"),
}


def test_no_chain_calls_a_zero_balance_tokenhub_model():
    """Dead TokenHub IDs must not sit in any chain."""
    offenders = [
        (slot.value, account, model)
        for slot, chain in llm_router.SLOT_MODELS.items()
        for account, model in chain
        if (account, model) in _TOKENHUB_EXHAUSTED
    ]
    assert not offenders, f"zero-balance TokenHub models still in chains: {offenders}"


def test_verified_tokenhub_models_are_registered_and_in_the_text_tail():
    for pair in _TOKENHUB_VERIFIED:
        assert pair in llm_router._SAFE_MODELS, (
            f"{pair} not registered -> _refuse_reason blocks it"
        )
        assert pair in llm_router._TEXT_TAIL, (
            f"{pair} missing from the non-DashScope floor"
        )


def test_negative_confidence_pill_sits_after_the_non_aliyun_floor_in_review():
    """REVIEW must reach the TokenHub floor before the negative-confidence model.

    aliyun_c/deepseek-v3.2 returns a correct plan with confidence -0.95. The
    router sees HTTP 200 and stops, so anything ordered after it is dead code
    once the Aliyun quota is gone -- which is every afternoon.
    """
    chain = llm_router.SLOT_MODELS[SLOT.REVIEW]
    pill = chain.index(("aliyun_c", "deepseek-v3.2"))
    floor_positions = [chain.index(p) for p in _TOKENHUB_VERIFIED if p in chain]
    assert floor_positions, "REVIEW cannot reach any verified TokenHub model"
    assert pill > max(floor_positions), (
        f"negative-confidence model at {pill} precedes the TokenHub floor at "
        f"{sorted(floor_positions)} -> floor unreachable"
    )


def test_tokenhub_kimi_is_forced_to_temperature_one():
    """TokenHub rejects any temperature but 1 for the kimi family (HTTP 400
    'invalid temperature: only 1 is allowed for this model')."""
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "kimi-k2.6", {"model": "kimi-k2.6", "temperature": 0},
    )
    assert out["temperature"] == 1


def test_forced_temperature_is_tokenhub_scoped_not_model_name_scoped():
    """The constraint belongs to TokenHub, not to the model name -- the same kimi
    on DashScope accepts temperature=0 and must keep it."""
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "aliyun_c", "kimi-k2.6", {"model": "kimi-k2.6", "temperature": 0},
    )
    assert out["temperature"] == 0


def test_tokenhub_thinking_models_get_a_max_tokens_floor():
    """These ignore enable_thinking=false and spend the whole allowance on
    reasoning_content, returning EMPTY content at max_tokens=500 (measured:
    finish_reason='length', reasoning_tokens=500). Raising the ceiling is what
    makes them answer at all."""
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "minimax-m2.7",
        {"model": "minimax-m2.7", "max_tokens": 500},
    )
    assert out["max_tokens"] >= 1600


def test_max_tokens_floor_never_lowers_a_callers_larger_budget():
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "minimax-m2.7",
        {"model": "minimax-m2.7", "max_tokens": 4000},
    )
    assert out["max_tokens"] == 4000


def test_max_tokens_floor_does_not_touch_models_without_the_problem():
    out = llm_router._apply_slot_params(
        SLOT.REVIEW, "tencent", "hy-mt2-pro",
        {"model": "hy-mt2-pro", "max_tokens": 500},
    )
    assert out["max_tokens"] == 500


@pytest.mark.asyncio
async def test_call_chain_content_validator_rejects_a_200_and_falls_through(monkeypatch):
    """A caller-supplied content gate must make the cascade continue.

    This is the poison-pill fix: `_validate_output` cannot know that a
    syntactically fine JSON plan carries an out-of-contract confidence, so the
    caller that owns the contract supplies the predicate.
    """
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 7, 10))
    pill = {"choices": [{"message": {"content": '{"intent":"X","confidence":-1.0}'}}]}
    good = {"choices": [{"message": {"content": '{"intent":"X","confidence":0.95}'}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=pill),
        "aliyun_b": _fake_response(200, json_payload=good),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    def reject_negative_confidence(content):
        import json as _json
        try:
            conf = float(_json.loads(content).get("confidence"))
        except Exception:
            return "unparseable"
        return "negative_confidence" if conf < 0 else None

    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "hi"}]},
        content_validator=reject_negative_confidence,
    )
    assert result == good
    assert len(client.call_log) >= 2


@pytest.mark.asyncio
async def test_call_chain_without_a_content_validator_keeps_the_first_200(monkeypatch):
    """No validator -> unchanged behavior (the gate is opt-in per caller)."""
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 7, 10))
    pill = {"choices": [{"message": {"content": '{"intent":"X","confidence":-1.0}'}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=pill),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": "hi"}]})
    assert result == pill


@pytest.mark.asyncio
async def test_a_raising_content_validator_does_not_kill_the_request(monkeypatch):
    """A buggy predicate must degrade to 'reject this candidate', never to a
    500 for the user."""
    _patch_keys(monkeypatch)
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 7, 10))
    first = {"choices": [{"message": {"content": "text one"}}]}
    second = {"choices": [{"message": {"content": "text two"}}]}
    client = _ScriptedClient({
        "aliyun_a": _fake_response(200, json_payload=first),
        "aliyun_b": _fake_response(200, json_payload=second),
    })
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    calls = {"n": 0}

    def explodes_once(content):
        calls["n"] += 1
        if calls["n"] == 1:
            raise ValueError("boom")
        return None

    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "hi"}]},
        content_validator=explodes_once,
    )
    assert result == second


# ════════════════════════════════════════════════════════════════════════
# ark (Volcengine 火山方舟) — provider wiring + the billing premise it rests on.
#
# Ark differs from every other provider in the registry on the ONE axis this
# registry exists to protect: it bills post-paid after the free grant unless
# 安心体验模式 is on. So the invariant tests below are not ceremony — an ark
# entry reachable from a chain while that mode is off is a live money leak the
# router cannot detect (it cannot tell a paid 200 from a free one).
# ════════════════════════════════════════════════════════════════════════

def test_ark_provider_config_reads_its_own_env(monkeypatch):
    monkeypatch.setenv("LLM_ARK_API_KEY", "ark_key_fake")
    monkeypatch.delenv("LLM_ARK_BASE_URL", raising=False)
    base, key = llm_router._provider_config("ark")
    assert key == "ark_key_fake"
    assert base == "https://ark.cn-beijing.volces.com/api/v3"


def test_ark_base_url_is_overridable(monkeypatch):
    monkeypatch.setenv("LLM_ARK_API_KEY", "ark_key_fake")
    monkeypatch.setenv("LLM_ARK_BASE_URL", "https://ark.example/api/v3")
    base, _ = llm_router._provider_config("ark")
    assert base == "https://ark.example/api/v3"


def test_ark_without_a_key_is_unreachable(monkeypatch):
    """No key → call_chain skips it. Keeps an un-provisioned environment (CI, a
    dev box) from turning ark entries into hard failures."""
    monkeypatch.delenv("LLM_ARK_API_KEY", raising=False)
    _base, key = llm_router._provider_config("ark")
    assert not key


def test_every_ark_chain_entry_is_registered():
    """Duplicates the global invariant on purpose, scoped to ark, so a failure
    names the provider whose billing premise is conditional."""
    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            if account != "ark":
                continue
            assert (account, model) in llm_router._SAFE_MODELS, (
                f"{slot.value}: ark/{model} not registered — with 安心体验模式 off "
                f"an unregistered ark call bills silently"
            )


def test_ark_models_carry_a_dated_callable_id():
    """Ark rejects the console display name: `doubao-seed-1.6` → 404
    InvalidEndpointOrModel.NotFound, while `doubao-seed-1-6-251015` works. Every
    registered ark id must therefore end in a date-ish suffix, which is what
    GET /api/v3/models returns.

    Exception: `doubao-seed-evolving` is an undated id that genuinely exists —
    but the console shows it 未开通 for this account, so it must not be
    registered at all, and this test doubles as that check.
    """
    import re
    for account, model in llm_router._SAFE_MODELS:
        if account != "ark":
            continue
        assert re.search(r"-\d{6,8}$", model), (
            f"ark/{model} has no dated suffix — probably a console display name, "
            f"which Ark 404s. Take ids from GET /api/v3/models."
        )
